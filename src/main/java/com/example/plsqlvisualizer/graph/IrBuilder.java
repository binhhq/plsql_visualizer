package com.example.plsqlvisualizer.graph;

import com.example.plsqlvisualizer.db.ObjectRow;
import com.example.plsqlvisualizer.extract.CallGraphExtractor;
import com.example.plsqlvisualizer.extract.DictionarySnapshot;
import com.example.plsqlvisualizer.extract.DynamicSqlFlagger;
import com.example.plsqlvisualizer.extract.GraphAccumulator;
import com.example.plsqlvisualizer.extract.SynonymResolver;
import com.example.plsqlvisualizer.extract.TriggerExtractor;
import com.example.plsqlvisualizer.extract.WriteExtractor;
import com.example.plsqlvisualizer.model.Edge;
import com.example.plsqlvisualizer.model.Ir;
import com.example.plsqlvisualizer.model.Meta;
import com.example.plsqlvisualizer.model.Node;
import com.example.plsqlvisualizer.model.StaticSource;
import com.example.plsqlvisualizer.model.TraceSource;
import com.example.plsqlvisualizer.model.Unit;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Runs every extraction pass and assembles the result into the IR (design.md §6).
 *
 * <p>Pass order matters only in that the trigger pass must see the same resolver
 * as the write pass; otherwise each pass contributes independently to a shared
 * accumulator, and the ordering happens once at the end over all edges together.
 */
public class IrBuilder {

    private final DictionarySnapshot snapshot;

    public IrBuilder(DictionarySnapshot snapshot) {
        this.snapshot = snapshot;
    }

    /**
     * @param entryPoint {@code UNIT.SUBPROGRAM} to walk from, or null to start
     *        from every unit nothing calls
     */
    public Ir build(String entryPoint) {
        GraphAccumulator graph = extract();
        return assemble(graph.nodes(), graph.edges(), entryPoint);
    }

    /**
     * Runs every extraction pass over the snapshot, unordered.
     *
     * <p>Public because the incremental refresh runs the same passes over a
     * snapshot narrowed to the stale units — the edges a unit produces must not
     * depend on whether it was rebuilt alone or with the whole schema.
     */
    public GraphAccumulator extract() {
        SynonymResolver resolver = new SynonymResolver(snapshot.synonyms(), snapshot.schema());
        GraphAccumulator graph = new GraphAccumulator();

        new WriteExtractor(snapshot, resolver).extractInto(graph);
        new CallGraphExtractor(snapshot).extractInto(graph);
        new DynamicSqlFlagger(snapshot).extractInto(graph);
        new TriggerExtractor(snapshot, resolver).extractInto(graph);

        return graph;
    }

    /**
     * Orders an edge population and wraps it in fresh meta.
     *
     * <p>Ordering happens over the whole graph, so a refresh that rebuilt one unit
     * still hands the complete edge set here: a step ordinal is a position in the
     * walk from the entry point, and that walk crosses units.
     */
    public Ir assemble(List<Node> nodes, List<Edge> edges, String entryPoint) {
        CallGraph callGraph = new CallGraph(edges);
        StepOrdinal ordinal = new StepOrdinal(edges, callGraph);

        List<String> entryPoints = resolveEntryPoints(entryPoint, callGraph, ordinal);
        List<Edge> ordered = ordinal.order(entryPoints);

        return new Ir(buildMeta(entryPoint), nodes, ordered);
    }

    private List<String> resolveEntryPoints(String entryPoint, CallGraph callGraph,
                                            StepOrdinal ordinal) {
        if (entryPoint == null || entryPoint.isBlank()) {
            return ordinal.defaultEntryPoints();
        }
        String nodeId = toNodeId(entryPoint);
        if (!callGraph.hasVertex(nodeId)) {
            throw new IllegalArgumentException(
                    "Entry point not found in the call graph: " + nodeId);
        }
        return List.of(nodeId);
    }

    /** Accepts {@code UNIT.SUBPROGRAM}, or a full {@code PROC:...} node id. */
    private String toNodeId(String entryPoint) {
        if (entryPoint.startsWith("PROC:")) {
            return entryPoint;
        }
        String qualified = entryPoint.toUpperCase();
        // A schema-qualified entry point already carries the owner.
        long dots = qualified.chars().filter(c -> c == '.').count();
        return dots >= 2 ? "PROC:" + qualified : "PROC:" + snapshot.schema() + "." + qualified;
    }

    private Meta buildMeta(String entryPoint) {
        List<Unit> units = snapshot.objects().stream()
                .sorted(Comparator.comparing((ObjectRow row) -> row.unit().name())
                        .thenComparing(row -> row.unit().type()))
                .map(row -> new Unit(snapshot.schema(), row.unit().name(), row.unit().type(),
                        row.lastDdlTime()))
                .toList();

        String qualifiedEntryPoint = entryPoint == null || entryPoint.isBlank()
                ? null
                : toNodeId(entryPoint).substring("PROC:".length());

        return new Meta(
                Meta.SCHEMA_VERSION,
                snapshot.schema(),
                qualifiedEntryPoint,
                new StaticSource(Instant.now(), units),
                TraceSource.absent());
    }
}
