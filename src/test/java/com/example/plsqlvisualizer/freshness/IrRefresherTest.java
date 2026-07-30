package com.example.plsqlvisualizer.freshness;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.plsqlvisualizer.FixtureSchema;
import com.example.plsqlvisualizer.db.DictionaryClient;
import com.example.plsqlvisualizer.db.UnitKey;
import com.example.plsqlvisualizer.extract.DictionarySnapshot;
import com.example.plsqlvisualizer.graph.IrBuilder;
import com.example.plsqlvisualizer.model.Confidence;
import com.example.plsqlvisualizer.model.Edge;
import com.example.plsqlvisualizer.model.EdgeType;
import com.example.plsqlvisualizer.model.Ir;
import com.example.plsqlvisualizer.model.Meta;
import com.example.plsqlvisualizer.model.Node;
import com.example.plsqlvisualizer.model.Provenance;
import com.example.plsqlvisualizer.model.StaticSource;
import com.example.plsqlvisualizer.model.Unit;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The contract of an incremental refresh: <em>it produces what a full extraction
 * would</em> (design.md §7). Reading less of the dictionary is the only difference
 * allowed, so every test here compares a spliced IR against a rebuilt-from-scratch
 * one rather than against hand-written expectations — a cheaper graph that is also
 * a different graph would be worse than no incremental path at all.
 *
 * <p>Staleness is induced by backdating the {@code last_ddl_time} the IR recorded,
 * not by recompiling in the fixture schema. Same code path, no shared mutable
 * state, and the suite stays parallel-safe.
 */
class IrRefresherTest {

    private IrRefresher.Result refresh(Ir ir) throws Exception {
        FixtureSchema.requireDatabase();
        try (DictionaryClient client = DictionaryClient.connect(
                FixtureSchema.URL, FixtureSchema.USER, FixtureSchema.PASSWORD)) {
            return new IrRefresher(client).refresh(ir, null);
        }
    }

    /** A full extraction, for the equality every test measures against. */
    private Ir fullExtract() throws Exception {
        FixtureSchema.requireDatabase();
        try (DictionaryClient client = DictionaryClient.connect(
                FixtureSchema.URL, FixtureSchema.USER, FixtureSchema.PASSWORD)) {
            return new IrBuilder(new DictionarySnapshot(client)).build(FixtureSchema.ENTRY_POINT);
        }
    }

    /** Everything a renderer draws. Meta is excluded: generated_at moves every run. */
    private void assertDrawsTheSameGraph(Ir actual, Ir expected) {
        assertThat(actual.edges())
                .as("edge list, in step order, ids and all")
                .isEqualTo(expected.edges());
        assertThat(actual.nodes())
                .as("node set (order is the previous IR's, so it may differ)")
                .containsExactlyInAnyOrderElementsOf(expected.nodes());
    }

    @Test
    @DisplayName("a fresh IR is handed back untouched, with nothing re-read")
    void freshIrIsUntouched() throws Exception {
        Ir fresh = FixtureSchema.ir();

        IrRefresher.Result result = refresh(fresh);

        assertThat(result.changedAnything()).isFalse();
        assertThat(result.ir()).isSameAs(fresh);
        assertThat(result.reextracted()).isEmpty();
        assertThat(result.fullRebuild()).isFalse();
    }

    @Test
    @DisplayName("splicing one stale unit yields the same graph as a full extraction")
    void splicedUnitMatchesFullExtraction() throws Exception {
        Ir stale = backdate(FixtureSchema.ir(), unit -> "PKG_VALIDATE".equals(unit.name()));

        IrRefresher.Result result = refresh(stale);

        assertThat(result.fullRebuild()).as("the incremental path must handle this").isFalse();
        assertThat(result.reextracted())
                .as("only the stale unit is read again — that is the whole point")
                .containsExactly(UnitKey.of("PKG_VALIDATE", "PACKAGE BODY"));
        assertDrawsTheSameGraph(result.ir(), fullExtract());
    }

    @Test
    @DisplayName("the refreshed IR records the live LAST_DDL_TIME, so it reports fresh again")
    void refreshClearsTheStaleness() throws Exception {
        Ir stale = backdate(FixtureSchema.ir(), unit -> "PKG_VALIDATE".equals(unit.name()));

        Ir refreshed = refresh(stale).ir();

        assertThat(refresh(refreshed).changedAnything())
                .as("a refresh that leaves the IR stale would loop forever")
                .isFalse();
    }

    @Test
    @DisplayName("every unit stale at once still equals a full extraction — no edge is counted twice")
    void wholeSchemaStaleMatchesFullExtraction() throws Exception {
        Ir stale = backdate(FixtureSchema.ir(), unit -> true);

        IrRefresher.Result result = refresh(stale);

        assertThat(result.reextracted()).hasSameSizeAs(FixtureSchema.ir().meta().staticSource().units());
        assertDrawsTheSameGraph(result.ir(), fullExtract());
    }

    @Test
    @DisplayName("a stale trigger keeps exactly one trigger-induced edge")
    void staleTriggerMatchesFullExtraction() throws Exception {
        Ir stale = backdate(FixtureSchema.ir(), unit -> "TRIGGER".equals(unit.type()));

        IrRefresher.Result result = refresh(stale);

        // The edge leaves a table, so the node it comes from cannot say who wrote
        // it; via_trigger is the only thing tying it back to a unit. If that link
        // broke, the edge would be dropped here or emitted twice.
        assertThat(result.ir().edges())
                .filteredOn(e -> e.confidence() == Confidence.TRIGGER_INDUCED)
                .hasSize(1);
        assertDrawsTheSameGraph(result.ir(), fullExtract());
    }

    @Test
    @DisplayName("a unit the schema no longer has is pruned, without re-reading anything")
    void removedUnitIsPrunedWithoutReextraction() throws Exception {
        Ir withGhost = withGhostUnit(FixtureSchema.ir());

        IrRefresher.Result result = refresh(withGhost);

        assertThat(result.pruned()).contains(UnitKey.of("PKG_GONE", "PACKAGE BODY"));
        assertThat(result.reextracted())
                .as("pruning reads nothing; an empty restriction must not mean 'the whole schema'")
                .isEmpty();
        assertThat(result.ir().edges())
                .as("the dropped unit's edge is gone")
                .noneMatch(e -> "PROC:GHOST.PKG_GONE.VANISHED".equals(e.from()));
        assertDrawsTheSameGraph(result.ir(), fullExtract());
    }

    @Test
    @DisplayName("an edge that cannot name its unit forces a full extraction, loudly")
    void unownedEdgeFallsBackToFullExtraction() throws Exception {
        Ir stale = withUnattributableEdge(
                backdate(FixtureSchema.ir(), unit -> "PKG_VALIDATE".equals(unit.name())));

        IrRefresher.Result result = refresh(stale);

        assertThat(result.fullRebuild()).isTrue();
        assertThat(result.fallbackReason()).contains("do not record which unit produced them");
        assertDrawsTheSameGraph(result.ir(), fullExtract());
    }

    // ------------------------------------------------------------------ fixtures

    /** Rewinds the recorded DDL time of the matching units, so they read as changed. */
    private Ir backdate(Ir ir, Predicate<Unit> stale) {
        return withUnits(ir, units -> units.stream()
                .map(u -> stale.test(u)
                        ? new Unit(u.owner(), u.name(), u.type(), Instant.EPOCH)
                        : u)
                .toList());
    }

    /** A unit the IR believes in, with an edge to its name, that the schema does not have. */
    private Ir withGhostUnit(Ir ir) {
        List<Unit> units = new ArrayList<>(ir.meta().staticSource().units());
        units.add(new Unit("GHOST", "PKG_GONE", "PACKAGE BODY", Instant.EPOCH));

        List<Node> nodes = new ArrayList<>(ir.nodes());
        nodes.add(Node.programUnit("GHOST", "PKG_GONE", "VANISHED", "PACKAGE BODY"));

        List<Edge> edges = new ArrayList<>(ir.edges());
        edges.add(Edge.builder()
                .id("ghost")
                .type(EdgeType.CALL)
                .from("PROC:GHOST.PKG_GONE.VANISHED")
                .to(ir.edges().get(0).from())
                .line(1)
                .confidence(Confidence.RESOLVED)
                .provenance(List.of(Provenance.STATIC))
                .build());

        return new Ir(withUnits(ir, existing -> units).meta(), nodes, edges);
    }

    /** An edge whose source node is absent, so nothing can say which unit emitted it. */
    private Ir withUnattributableEdge(Ir ir) {
        List<Edge> edges = new ArrayList<>(ir.edges());
        edges.add(Edge.builder()
                .id("orphan")
                .type(EdgeType.WRITE)
                .from("PROC:NOWHERE.UNLISTED.MYSTERY")
                .to(Node.UNKNOWN_ID)
                .line(1)
                .confidence(Confidence.RESOLVED)
                .provenance(List.of(Provenance.STATIC))
                .build());
        return new Ir(ir.meta(), ir.nodes(), edges);
    }

    private Ir withUnits(Ir ir, UnaryOperator<List<Unit>> rewrite) {
        StaticSource source = ir.meta().staticSource();
        StaticSource rewritten = new StaticSource(
                source.generatedAt(), rewrite.apply(source.units()));
        Meta meta = new Meta(ir.meta().schemaVersion(), ir.meta().db(), ir.meta().entryPoint(),
                rewritten, ir.meta().traceSource());
        return new Ir(meta, ir.nodes(), ir.edges());
    }
}
