package com.example.plsqlvisualizer.trace;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.plsqlvisualizer.model.Op;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Parses the committed fixture traces — real files, captured by
 * {@code scripts/capture-trace.sh} from the fixture schema. No database: a trace
 * is a file, and so is the IR it annotates.
 *
 * <p>The fixtures are captured <em>cold</em> (shared pool flushed), so ~98% of
 * each file is Oracle re-reading its own dictionary. That noise is the point.
 * A warm trace of the same flow is 370 lines of nothing but the application's own
 * SQL, and a parser that only ever sees that is not tested against anything real.
 */
class TraceParserTest {

    private static final Path HOSE =
            Path.of("src/test/resources/traces/place_order_hose.trc");

    /** The IR's sql_id for each statement the fixture flow executes. */
    private static final String UPDATE_VALIDATE_STATS = "g1cmhh2ukhv7m";
    private static final String INSERT_ORDERS = "5h5s33cmnyb8f";
    private static final String TRIGGER_INSERT_AUDIT = "77u5ax58fxzt1";
    private static final String MERGE_POSITIONS = "bsrz113mutwk4";
    private static final String DYNAMIC_INSERT = "0f90zx1jvsgas";

    private static List<TraceEvent> events;

    @BeforeAll
    static void parseOnce() throws Exception {
        events = new TraceParser().parse(HOSE);
    }

    private TraceEvent event(String sqlId) {
        return events.stream()
                .filter(e -> sqlId.equals(e.sqlId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no execution event for sqlid " + sqlId));
    }

    @Test
    @DisplayName("finds the application's statements inside a trace that is mostly dictionary noise")
    void findsApplicationStatementsAmongTheNoise() {
        assertThat(events)
                .as("a cold capture is dominated by Oracle's recursive lookups; if this "
                        + "count collapses, the fixture was re-captured warm and proves less")
                .hasSizeGreaterThan(400);

        assertThat(events).extracting(TraceEvent::sqlId)
                .contains(UPDATE_VALIDATE_STATS, INSERT_ORDERS, TRIGGER_INSERT_AUDIT,
                        MERGE_POSITIONS, DYNAMIC_INSERT);
    }

    @Test
    @DisplayName("reads a dynamic write's real target off the statement text")
    void readsTheDynamicTarget() {
        TraceEvent dynamic = event(DYNAMIC_INSERT);

        // The whole reason the trace lane exists: static analysis cannot know this
        // name, and the dictionary has no sql_id for the statement at all.
        assertThat(dynamic.op()).isEqualTo(Op.INSERT);
        assertThat(dynamic.target()).isEqualTo("ORDER_LOG_202607");
        assertThat(dynamic.isWrite()).isTrue();
    }

    @Test
    @DisplayName("a trigger's own write is recorded one level deeper than the write that fires it")
    void triggerWriteIsDeeper() {
        assertThat(event(TRIGGER_INSERT_AUDIT).depth())
                .as("trigger SQL runs as recursive SQL from the INSERT")
                .isEqualTo(2);
        assertThat(event(INSERT_ORDERS).depth()).isEqualTo(1);
    }

    @Test
    @DisplayName("depth alone cannot separate our SQL from Oracle's — both appear at depth 1")
    void depthIsNotANoiseFilter() {
        List<TraceEvent> atDepthOne = events.stream().filter(e -> e.depth() == 1).toList();

        assertThat(atDepthOne).extracting(TraceEvent::sqlId).contains(INSERT_ORDERS);
        assertThat(atDepthOne)
                .as("Oracle's dictionary lookups run at depth 1 too, which is why the "
                        + "merger filters by sql_id and the $ naming convention instead")
                .anyMatch(e -> e.sql().contains("idl_ub1$") || e.sql().contains("procedure$"));
    }

    @Test
    @DisplayName("the word after UPDATE is not mistaken for the table name")
    void doesNotTreatSetAsATable() {
        TraceEvent sequenceBookkeeping = events.stream()
                .filter(e -> e.sql().toLowerCase().startsWith("update seq$"))
                .findFirst()
                .orElseThrow();

        // "update seq$ set increment$=:2" — reading the word after the object as a
        // qualified table name yields "SET", which then passes every filter that
        // looks for Oracle's $ convention and shows up as a phantom write.
        assertThat(sequenceBookkeeping.target()).isEqualTo("SEQ$");
    }

    @Test
    @DisplayName("statement text spanning several lines is reassembled")
    void reassemblesMultiLineStatements() {
        String merge = event(MERGE_POSITIONS).sql();

        assertThat(merge).contains("MERGE INTO POSITIONS");
        assertThat(merge).contains("WHEN MATCHED");
        assertThat(merge).contains("WHEN NOT MATCHED");
    }

    @Test
    @DisplayName("reader order puts a trigger's write after the write that fired it, not before")
    void readerOrderUndoesTheNesting() {
        int recordedParent = indexOf(events, INSERT_ORDERS);
        int recordedTrigger = indexOf(events, TRIGGER_INSERT_AUDIT);

        // As recorded: the nested statement finishes first, so the file lists the
        // audit write before the INSERT that caused it. Sorting by tim keeps that.
        assertThat(recordedTrigger)
                .as("the file really does record the child first")
                .isLessThan(recordedParent);

        List<TraceEvent> ordered = TraceOrder.readerOrder(events);

        assertThat(indexOf(ordered, TRIGGER_INSERT_AUDIT))
                .as("what a reader needs: the audit row appears because of the INSERT")
                .isGreaterThan(indexOf(ordered, INSERT_ORDERS));
    }

    @Test
    @DisplayName("reader order keeps every event exactly once")
    void readerOrderLosesNothing() {
        List<TraceEvent> ordered = TraceOrder.readerOrder(events);

        assertThat(ordered).hasSameSizeAs(events);
        assertThat(ordered).containsExactlyInAnyOrderElementsOf(events);
    }

    private static int indexOf(List<TraceEvent> list, String sqlId) {
        for (int i = 0; i < list.size(); i++) {
            if (sqlId.equals(list.get(i).sqlId())) {
                return i;
            }
        }
        throw new AssertionError("no event for sqlid " + sqlId);
    }
}
