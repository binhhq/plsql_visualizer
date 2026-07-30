package com.example.plsqlvisualizer.trace;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.plsqlvisualizer.model.Confidence;
import com.example.plsqlvisualizer.model.Edge;
import com.example.plsqlvisualizer.model.EdgeType;
import com.example.plsqlvisualizer.model.Ir;
import com.example.plsqlvisualizer.model.IrJson;
import com.example.plsqlvisualizer.model.Node;
import com.example.plsqlvisualizer.model.Op;
import com.example.plsqlvisualizer.model.Provenance;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The overlay's contract: the static lane stays authoritative and the trace only
 * adds (design.md §5). One execution proves what happened once, never what the
 * code can do — so nothing here is allowed to delete an edge, move a target, or
 * downgrade a confidence.
 *
 * <p>Two scenarios, and the second is the one that matters. {@code place_order_hose}
 * runs every statement, which proves the sql_id join works. {@code place_order_hnx}
 * takes the other branch, so a write that is statically present never executes —
 * the gap between "can" and "did" that the whole tool exists to show.
 */
class TraceMergerTest {

    private static final Path STATIC_IR = Path.of("src/test/resources/ir-place-order.json");
    private static final Path HOSE = Path.of("src/test/resources/traces/place_order_hose.trc");
    private static final Path HNX = Path.of("src/test/resources/traces/place_order_hnx.trc");

    private Ir staticIr() throws Exception {
        return IrJson.read(STATIC_IR);
    }

    private Ir merged(Path trace, String scenario) throws Exception {
        return new TraceMerger(scenario, Instant.parse("2026-07-30T10:00:00Z"))
                .merge(staticIr(), new TraceParser().parse(trace));
    }

    private List<Edge> traced(Ir ir) {
        return ir.edges().stream()
                .filter(e -> e.traceOrder() != null)
                .sorted(Comparator.comparing(Edge::traceOrder))
                .toList();
    }

    private Edge byStep(Ir ir, int step) {
        return ir.edges().stream()
                .filter(e -> Integer.valueOf(step).equals(e.step()))
                .findFirst()
                .orElseThrow();
    }

    // ------------------------------------------------------------------ hose

    @Test
    @DisplayName("every executed statement gets a dense trace_order and trace provenance")
    void stampsExecutedStatements() throws Exception {
        Ir ir = merged(HOSE, "place_order_hose");

        List<Edge> traced = traced(ir);
        assertThat(traced).hasSize(10);
        assertThat(traced).extracting(Edge::traceOrder)
                .as("dense 1..N so the renderer can index it the way it indexes step")
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        // A statement the dictionary knew about and the trace confirmed carries both
        // lanes. The trace-only edge among these carries just the trace — claiming
        // static provenance for a write static could not name would be a lie.
        assertThat(traced).filteredOn(e -> e.confidence() != Confidence.TRACE_RESOLVED)
                .allSatisfy(e -> assertThat(e.provenance())
                        .containsExactly(Provenance.STATIC, Provenance.TRACE));
        assertThat(traced).filteredOn(e -> e.confidence() == Confidence.TRACE_RESOLVED)
                .allSatisfy(e -> assertThat(e.provenance()).containsExactly(Provenance.TRACE));
        assertThat(ir.meta().traceSource().present()).isTrue();
        assertThat(ir.meta().traceSource().scenario()).isEqualTo("place_order_hose");
    }

    @Test
    @DisplayName("the trigger's write is ordered after the write that fired it")
    void triggerFollowsItsCause() throws Exception {
        Ir ir = merged(HOSE, "place_order_hose");

        Edge insertOrders = byStep(ir, 3);
        Edge triggerWrite = byStep(ir, 4);

        assertThat(triggerWrite.confidence()).isEqualTo(Confidence.TRIGGER_INDUCED);
        assertThat(triggerWrite.traceOrder())
                .as("the trace file records the child first; the merge must not")
                .isEqualTo(insertOrders.traceOrder() + 1);
    }

    @Test
    @DisplayName("a dynamic write the trace resolved becomes its own edge, linked to the unknown")
    void resolvesTheDynamicWrite() throws Exception {
        Ir ir = merged(HOSE, "place_order_hose");

        Edge unknown = ir.edges().stream()
                .filter(e -> e.confidence() == Confidence.DYNAMIC_UNKNOWN)
                .findFirst()
                .orElseThrow();
        Edge resolved = ir.edges().stream()
                .filter(e -> e.confidence() == Confidence.TRACE_RESOLVED)
                .findFirst()
                .orElseThrow();

        assertThat(resolved.to()).isEqualTo(Node.tableId(ir.meta().db(), "ORDER_LOG_202607"));
        assertThat(resolved.from())
                .as("attributed to the subprogram whose dynamic statement it explains")
                .isEqualTo(unknown.from());
        assertThat(resolved.resolves()).isEqualTo(unknown.id());
        assertThat(resolved.op()).isEqualTo(Op.INSERT);
        assertThat(resolved.provenance()).containsExactly(Provenance.TRACE);
        assertThat(resolved.step())
                .as("a trace-only edge has no position in the static walk")
                .isNull();

        assertThat(unknown.to())
                .as("static could not name this target and still cannot — the trace "
                        + "explains one run, it does not rewrite what the code says")
                .isEqualTo(Node.UNKNOWN_ID);
        assertThat(ir.nodes()).anyMatch(n -> n.id().equals(resolved.to()));
    }

    @Test
    @DisplayName("Oracle's own recursive DML never becomes an edge")
    void ignoresDictionaryWrites() throws Exception {
        Ir ir = merged(HOSE, "place_order_hose");

        // The flow bumps a sequence, so the trace contains "update seq$ ...".
        assertThat(ir.meta().traceSource().unattributed())
                .as("dictionary writes are filtered, not counted as mysteries")
                .isNull();
        assertThat(ir.edges()).noneMatch(e -> e.to() != null && e.to().contains("$")
                && !e.to().equals(Node.UNKNOWN_ID));
    }

    @Test
    @DisplayName("call edges are left unstamped — 10046 records SQL, not PL/SQL calls")
    void callsAreNotStamped() throws Exception {
        Ir ir = merged(HOSE, "place_order_hose");

        assertThat(ir.edges()).filteredOn(e -> e.type() == EdgeType.CALL)
                .isNotEmpty()
                .allSatisfy(e -> {
                    assertThat(e.traceOrder()).isNull();
                    assertThat(e.provenance()).doesNotContain(Provenance.TRACE);
                });
    }

    @Test
    @DisplayName("the overlay adds; it never edits a static fact")
    void staticFactsSurviveUntouched() throws Exception {
        Ir before = staticIr();
        Ir after = merged(HOSE, "place_order_hose");

        for (Edge original : before.edges()) {
            Edge same = after.edges().stream()
                    .filter(e -> e.id().equals(original.id()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("edge vanished: " + original.id()));

            assertThat(same.from()).isEqualTo(original.from());
            assertThat(same.to()).isEqualTo(original.to());
            assertThat(same.op()).isEqualTo(original.op());
            assertThat(same.step()).isEqualTo(original.step());
            assertThat(same.confidence()).isEqualTo(original.confidence());
            assertThat(same.reachability()).isEqualTo(original.reachability());
            assertThat(same.guard()).isEqualTo(original.guard());
        }
        assertThat(after.nodes()).containsAll(before.nodes());
    }

    // ------------------------------------------------------------------- hnx

    @Test
    @DisplayName("a statically-present write the run never reached stays static-only, and is counted")
    void reportsWhatNeverRan() throws Exception {
        Ir ir = merged(HNX, "place_order_hnx");

        // Step 7 is the branch-conditional INSERT guarded by IF p_market = 'HOSE'.
        Edge branchWrite = byStep(ir, 7);

        assertThat(branchWrite.guard()).contains("HOSE");
        assertThat(branchWrite.traceOrder())
                .as("no trace_order means the traced run never executed it")
                .isNull();
        assertThat(branchWrite.provenance())
                .as("still a real possibility in the code — static provenance stands")
                .containsExactly(Provenance.STATIC);
        assertThat(ir.meta().traceSource().notExecuted()).isEqualTo(1);
    }

    @Test
    @DisplayName("the other scenario still confirms everything that did run")
    void hnxConfirmsTheRest() throws Exception {
        Ir hnx = merged(HNX, "place_order_hnx");
        Ir hose = merged(HOSE, "place_order_hose");

        assertThat(traced(hnx)).hasSize(traced(hose).size() - 1);
        assertThat(hnx.edges()).filteredOn(e -> e.confidence() == Confidence.TRACE_RESOLVED)
                .as("the dynamic write runs on both paths")
                .hasSize(1);
    }
}
