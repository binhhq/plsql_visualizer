package com.example.plsqlvisualizer;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.plsqlvisualizer.model.Confidence;
import com.example.plsqlvisualizer.model.Edge;
import com.example.plsqlvisualizer.model.EdgeType;
import com.example.plsqlvisualizer.model.Ir;
import com.example.plsqlvisualizer.model.Node;
import com.example.plsqlvisualizer.model.NodeKind;
import com.example.plsqlvisualizer.model.Op;
import com.example.plsqlvisualizer.model.Reachability;
import com.example.plsqlvisualizer.model.ResolvedVia;
import java.util.List;
import org.assertj.core.api.Condition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The known-answer suite from {@code docs/test-fixtures.md}.
 *
 * <p>The fixtures deliberately include the hard cases — dynamic SQL, a synonym,
 * a trigger, a call cycle, a branch, a loop — so a green run means the honesty
 * rules hold, not just the happy path. The negative assertions matter as much as
 * the positive ones: an edge the extractor invents is worse than one it admits
 * it cannot resolve.
 */
class ExtractorFixtureTest {

    private Ir ir() throws Exception {
        return FixtureSchema.ir();
    }

    private String schema() throws Exception {
        return ir().meta().db();
    }

    private String proc(String unit, String subprogram) throws Exception {
        return Node.procId(schema(), unit, subprogram);
    }

    private String table(String name) throws Exception {
        return Node.tableId(schema(), name);
    }

    private List<Edge> writesFrom(String fromId) throws Exception {
        return ir().edges().stream()
                .filter(e -> e.type() == EdgeType.WRITE && e.from().equals(fromId))
                .toList();
    }

    /** The single write from {@code fromId} to {@code toId} with the given op. */
    private Edge write(String fromId, Op op, String toId) throws Exception {
        List<Edge> matches = writesFrom(fromId).stream()
                .filter(e -> e.op() == op && e.to().equals(toId))
                .toList();
        assertThat(matches)
                .as("exactly one %s edge %s -> %s", op, fromId, toId)
                .hasSize(1);
        return matches.get(0);
    }

    // ---------------------------------------------------------------- calls

    @Test
    @DisplayName("fixture 6 + 10: cross-package call graph, including the cycle")
    void extractsExactlyTheExpectedCallEdges() throws Exception {
        List<Edge> calls = ir().edges().stream()
                .filter(e -> e.type() == EdgeType.CALL)
                .toList();

        assertThat(calls)
                .as("the signature join matches spec and body; duplicates must be collapsed")
                .hasSize(4);

        assertThat(calls).extracting(Edge::from, Edge::to).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple(
                        proc("PKG_ORDER", "SUBMIT"), proc("PKG_VALIDATE", "CHECK_ORDER")),
                org.assertj.core.groups.Tuple.tuple(
                        proc("PKG_ORDER", "SUBMIT"), proc("PKG_DYNAMIC", "LOG_DYNAMIC")),
                org.assertj.core.groups.Tuple.tuple(
                        proc("PKG_ORDER", "SUBMIT"), proc("PKG_POSITION", "APPLY_FILL")),
                org.assertj.core.groups.Tuple.tuple(
                        proc("PKG_POSITION", "APPLY_FILL"), proc("PKG_ORDER", "MARK_FILLED")));
    }

    @Test
    @DisplayName("fixture 10: the PKG_ORDER <-> PKG_POSITION cycle terminates")
    void traversalSurvivesTheCallCycle() throws Exception {
        // Reaching this assertion at all means the depth-first walk terminated.
        assertThat(ir().edges()).extracting(Edge::step).doesNotContainNull();
        assertThat(ir().edges()).extracting(Edge::id).doesNotHaveDuplicates();
    }

    // --------------------------------------------------------------- writes

    @Test
    @DisplayName("fixture 1: a validate proc that looks read-only still has one write")
    void extractsPlainWrite() throws Exception {
        Edge edge = write(proc("PKG_VALIDATE", "CHECK_ORDER"), Op.UPDATE, table("VALIDATE_STATS"));

        assertThat(edge.confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(edge.resolvedVia()).isEqualTo(ResolvedVia.DIRECT);
        assertThat(edge.reachability()).isEqualTo(Reachability.UNCONDITIONAL);
    }

    @Test
    @DisplayName("fixture 2 + 4: resolved-static writes")
    void extractsResolvedWrites() throws Exception {
        String submit = proc("PKG_ORDER", "SUBMIT");

        assertThat(write(submit, Op.INSERT, table("ORDERS")))
                .satisfies(e -> assertThat(e.resolvedVia()).isEqualTo(ResolvedVia.DIRECT));
        assertThat(write(submit, Op.UPDATE, table("CASH_BALANCE")))
                .satisfies(e -> assertThat(e.resolvedVia()).isEqualTo(ResolvedVia.DIRECT));
    }

    @Test
    @DisplayName("fixture 3: UPDATE via synonym ORD resolves to ORDERS")
    void resolvesSynonymToBaseTable() throws Exception {
        Edge edge = write(proc("PKG_ORDER", "SUBMIT"), Op.UPDATE, table("ORDERS"));

        assertThat(edge.resolvedVia())
                .as("the DML names ORD; the edge must land on ORDERS and say how")
                .isEqualTo(ResolvedVia.SYNONYM);
        assertThat(edge.confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(ir().nodes()).extracting(Node::name).doesNotContain("ORD");
    }

    @Test
    @DisplayName("fixture 5: the ORDER_LOG insert is branch-conditional and quotes its guard")
    void capturesBranchGuard() throws Exception {
        Edge edge = write(proc("PKG_ORDER", "SUBMIT"), Op.INSERT, table("ORDER_LOG"));

        assertThat(edge.reachability()).isEqualTo(Reachability.BRANCH_CONDITIONAL);
        assertThat(edge.guard())
                .as("PL/Scope has no IF context; the guard comes from the source scan")
                .isEqualTo("IF p_market = 'HOSE'");
    }

    @Test
    @DisplayName("fixture 8: MERGE stays one MERGE edge, not an INSERT plus an UPDATE")
    void keepsMergeAsASingleOp() throws Exception {
        Edge edge = write(proc("PKG_POSITION", "APPLY_FILL"), Op.MERGE, table("POSITIONS"));

        assertThat(edge.confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(edge.reachability()).isEqualTo(Reachability.UNCONDITIONAL);
    }

    @Test
    @DisplayName("fixture 9: the UPDATE inside FOR is marked as a loop")
    void detectsLoopReachability() throws Exception {
        Edge edge = write(proc("PKG_POSITION", "APPLY_FILL"), Op.UPDATE, table("POSITIONS"));

        assertThat(edge.reachability()).isEqualTo(Reachability.LOOP);
    }

    @Test
    @DisplayName("fixture: MARK_FILLED updates ORDERS")
    void extractsWriteFromTheCycleCallee() throws Exception {
        Edge edge = write(proc("PKG_ORDER", "MARK_FILLED"), Op.UPDATE, table("ORDERS"));

        assertThat(edge.confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(edge.resolvedVia()).isEqualTo(ResolvedVia.DIRECT);
    }

    // -------------------------------------------------------------- honesty

    @Test
    @DisplayName("fixture 7: EXECUTE IMMEDIATE lands on the sentinel, target never guessed")
    void flagsDynamicSqlWithoutGuessingTheTarget() throws Exception {
        Edge edge = write(proc("PKG_DYNAMIC", "LOG_DYNAMIC"), Op.INSERT, Node.UNKNOWN_ID);

        assertThat(edge.confidence()).isEqualTo(Confidence.DYNAMIC_UNKNOWN);
        assertThat(edge.resolvedVia()).as("nothing was resolved, so nothing is claimed").isNull();
        assertThat(edge.rawText())
                .as("the raw statement is carried so a reader can judge for themselves")
                .contains("INSERT INTO");

        assertThat(ir().nodes())
                .filteredOn(n -> n.kind() == NodeKind.UNKNOWN)
                .as("one shared sentinel node")
                .hasSize(1);
    }

    @Test
    @DisplayName("fixture 11: the trigger write is found even though SUBMIT never mentions it")
    void findsTriggerInducedWrite() throws Exception {
        List<Edge> triggered = ir().edges().stream()
                .filter(e -> e.confidence() == Confidence.TRIGGER_INDUCED)
                .toList();

        assertThat(triggered).hasSize(1);
        Edge edge = triggered.get(0);

        assertThat(edge.from())
                .as("caused by the write to ORDERS, not by the calling procedure")
                .isEqualTo(table("ORDERS"));
        assertThat(edge.to()).isEqualTo(table("ORDER_AUDIT"));
        assertThat(edge.op()).isEqualTo(Op.INSERT);
        assertThat(edge.viaTrigger()).isEqualTo("TRG_ORDER_AUDIT");

        assertThat(writesFrom(proc("PKG_ORDER", "SUBMIT")))
                .as("SUBMIT's own statements contain no write to ORDER_AUDIT")
                .extracting(Edge::to)
                .doesNotContain(table("ORDER_AUDIT"));
    }

    // --------------------------------------------------- negative assertions

    @Test
    @DisplayName("a SELECT must not become a write edge")
    void readsProduceNoEdges() throws Exception {
        assertThat(writesFrom(proc("PKG_VALIDATE", "CHECK_ORDER")))
                .as("CHECK_ORDER reads CASH_BALANCE and writes only VALIDATE_STATS")
                .hasSize(1)
                .allSatisfy(e -> assertThat(e.to()).isEqualTo(table("VALIDATE_STATS")));
    }

    @Test
    @DisplayName("the dynamic target is never resolved to a concrete table")
    void neverInventsTheDynamicTarget() throws Exception {
        assertThat(ir().nodes())
                .extracting(Node::name)
                .as("ORDER_LOG_YYYYMM exists only as a runtime string")
                .doesNotContain("ORDER_LOG_YYYYMM");

        assertThat(writesFrom(proc("PKG_DYNAMIC", "LOG_DYNAMIC")))
                .as("LOG_DYNAMIC writes exactly one thing, and it is unknown")
                .hasSize(1)
                .allSatisfy(e -> assertThat(e.to()).isEqualTo(Node.UNKNOWN_ID));
    }

    @Test
    @DisplayName("DUAL from the MERGE USING clause is not a write target")
    void ignoresSourceTablesOfDml() throws Exception {
        assertThat(ir().nodes()).extracting(Node::name).doesNotContain("DUAL");
        assertThat(ir().edges()).extracting(Edge::to).doesNotContain(table("DUAL"));
    }

    // ------------------------------------------------------------- ordering

    @Test
    @DisplayName("step order walks callees inline, in the sequence the design specifies")
    void ordersStepsByCallGraphTraversal() throws Exception {
        String submit = proc("PKG_ORDER", "SUBMIT");
        List<String> order = ir().edges().stream()
                .filter(e -> e.from().equals(submit))
                .map(e -> e.type() == EdgeType.CALL ? "CALL " + shortName(e.to())
                        : e.op() + " " + shortName(e.to()))
                .toList();

        assertThat(order).containsExactly(
                "CALL CHECK_ORDER",
                "INSERT ORDERS",
                "UPDATE ORDERS",
                "UPDATE CASH_BALANCE",
                "INSERT ORDER_LOG",
                "CALL LOG_DYNAMIC",
                "CALL APPLY_FILL");
    }

    @Test
    @DisplayName("the trigger edge is stepped through right after the write that fires it")
    void placesTriggerEdgeAfterItsCause() throws Exception {
        Edge insertOrders = write(proc("PKG_ORDER", "SUBMIT"), Op.INSERT, table("ORDERS"));
        Edge triggered = ir().edges().stream()
                .filter(e -> e.confidence() == Confidence.TRIGGER_INDUCED)
                .findFirst()
                .orElseThrow();

        assertThat(triggered.step()).isEqualTo(insertOrders.step() + 1);
    }

    @Test
    @DisplayName("every edge carries provenance and a unique step")
    void everyEdgeIsAttributable() throws Exception {
        assertThat(ir().edges())
                .isNotEmpty()
                .are(new Condition<>(e -> e.provenance() != null && !e.provenance().isEmpty(),
                        "has provenance"));
        assertThat(ir().edges()).extracting(Edge::step).doesNotHaveDuplicates();
    }

    private static String shortName(String nodeId) {
        return nodeId.substring(nodeId.lastIndexOf('.') + 1);
    }
}
