package com.example.plsqlvisualizer.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The IR is a frozen contract (design.md §6). These tests pin it against the
 * literal sample from the design doc: if a field name, enum spelling or
 * optionality ever drifts, the extractor and the renderer stop agreeing.
 */
class IrContractTest {

    private static final String SAMPLE = "/ir-contract-sample.json";

    private String sampleJson() throws Exception {
        try (InputStream in = getClass().getResourceAsStream(SAMPLE)) {
            assertThat(in).as("design.md §6 sample must be on the test classpath").isNotNull();
            return new String(in.readAllBytes());
        }
    }

    @Test
    void parsesTheDesignDocSample() throws Exception {
        Ir ir = IrJson.parse(sampleJson());

        assertThat(ir.meta().schemaVersion()).isEqualTo(Meta.SCHEMA_VERSION);
        assertThat(ir.meta().db()).isEqualTo("NEWFO_DEV");
        assertThat(ir.meta().entryPoint()).isEqualTo("APP.PKG_ORDER.SUBMIT");
        assertThat(ir.meta().staticSource().generatedAt()).isEqualTo(Instant.parse("2026-07-28T10:00:00Z"));
        assertThat(ir.meta().staticSource().units()).singleElement()
                .satisfies(u -> {
                    assertThat(u.owner()).isEqualTo("APP");
                    assertThat(u.name()).isEqualTo("PKG_ORDER");
                    assertThat(u.type()).isEqualTo("PACKAGE BODY");
                    assertThat(u.lastDdlTime()).isEqualTo(Instant.parse("2026-07-27T22:14:00Z"));
                });
        assertThat(ir.meta().traceSource().present()).isTrue();
        assertThat(ir.meta().traceSource().scenario()).isEqualTo("place_order_hose");

        assertThat(ir.nodes()).hasSize(3);
        assertThat(ir.edges()).hasSize(5);
    }

    @Test
    void mapsEveryNodeKind() throws Exception {
        Ir ir = IrJson.parse(sampleJson());

        assertThat(ir.nodes()).extracting(Node::kind)
                .containsExactly(NodeKind.PROGRAM_UNIT, NodeKind.TABLE, NodeKind.UNKNOWN);
        assertThat(ir.nodes().get(0).id()).isEqualTo(Node.procId("APP", "PKG_ORDER", "SUBMIT"));
        assertThat(ir.nodes().get(1).id()).isEqualTo(Node.tableId("APP", "ORDERS"));
        assertThat(ir.nodes().get(2).id()).isEqualTo(Node.UNKNOWN_ID);
    }

    @Test
    void mapsEveryEdgeEnum() throws Exception {
        Ir ir = IrJson.parse(sampleJson());

        Edge call = ir.edges().get(0);
        assertThat(call.type()).isEqualTo(EdgeType.CALL);
        assertThat(call.op()).as("call edges carry no DML op").isNull();
        assertThat(call.provenance()).containsExactly(Provenance.STATIC);

        Edge resolvedWrite = ir.edges().get(1);
        assertThat(resolvedWrite.type()).isEqualTo(EdgeType.WRITE);
        assertThat(resolvedWrite.op()).isEqualTo(Op.INSERT);
        assertThat(resolvedWrite.confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(resolvedWrite.resolvedVia()).isEqualTo(ResolvedVia.DIRECT);
        assertThat(resolvedWrite.sqlId()).isEqualTo("8kyysdc8m75ag");
        assertThat(resolvedWrite.traceOrder()).isEqualTo(5);
        assertThat(resolvedWrite.provenance()).containsExactly(Provenance.STATIC, Provenance.TRACE);

        Edge conditional = ir.edges().get(2);
        assertThat(conditional.reachability()).isEqualTo(Reachability.BRANCH_CONDITIONAL);
        assertThat(conditional.guard()).isEqualTo("IF p_market = 'HOSE'");

        Edge dynamic = ir.edges().get(3);
        assertThat(dynamic.confidence()).isEqualTo(Confidence.DYNAMIC_UNKNOWN);
        assertThat(dynamic.to()).isEqualTo(Node.UNKNOWN_ID);
        assertThat(dynamic.rawText()).isEqualTo("INSERT INTO ' || v_tbl || ' ...");

        Edge triggerInduced = ir.edges().get(4);
        assertThat(triggerInduced.confidence()).isEqualTo(Confidence.TRIGGER_INDUCED);
        assertThat(triggerInduced.viaTrigger()).isEqualTo("TRG_ORDER_AUDIT");
        assertThat(triggerInduced.from()).as("trigger edges start at the triggering table")
                .isEqualTo(Node.tableId("APP", "ORDERS"));
    }

    @Test
    void roundTripsWithoutLosingOrAddingFields() throws Exception {
        String original = sampleJson();

        JsonNode before = IrJson.mapper().readTree(original);
        JsonNode after = IrJson.mapper().readTree(IrJson.toJson(IrJson.parse(original)));

        assertThat(after).isEqualTo(before);
    }

    @Test
    void omitsNullsSoOptionalFieldsNeverLeakAsNull() throws Exception {
        Edge minimal = Edge.builder()
                .id("e1")
                .type(EdgeType.CALL)
                .from("PROC:APP.A.X")
                .to("PROC:APP.B.Y")
                .confidence(Confidence.RESOLVED)
                .build();

        String json = IrJson.mapper().writeValueAsString(minimal);

        assertThat(json).doesNotContain("null")
                .doesNotContain("op").doesNotContain("guard").doesNotContain("trace_order");
    }
}
