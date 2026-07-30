package com.example.plsqlvisualizer.graph;

import com.example.plsqlvisualizer.model.Confidence;
import com.example.plsqlvisualizer.model.Edge;
import com.example.plsqlvisualizer.model.EdgeType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Puts the edges in the order a reader should step through them (design.md §4.6).
 *
 * <p>Within a subprogram, {@code LINE} is the order. Across subprograms, the
 * order is the depth-first walk of the call graph from the entry point: when
 * {@code SUBMIT} calls {@code CHECK_ORDER} on line 7, everything
 * {@code CHECK_ORDER} does belongs between line 7 and line 9 of {@code SUBMIT}.
 *
 * <p>This is a <em>static</em> ordinal — a superset ordering. A branch that
 * never runs still gets a step. That is deliberate: the gap between this and a
 * real {@code trace_order} is the thing the tool exists to show.
 *
 * <p>Recursion is bounded by a visited set, so the {@code PKG_ORDER ↔
 * PKG_POSITION} cycle in the fixtures terminates instead of unwinding forever.
 */
public class StepOrdinal {

    private final List<Edge> edges;
    private final CallGraph callGraph;

    public StepOrdinal(List<Edge> edges, CallGraph callGraph) {
        this.edges = edges;
        this.callGraph = callGraph;
    }

    /**
     * Returns the edges in step order, each stamped with its {@code step} and a
     * stable {@code id}. Every input edge comes back exactly once — an edge that
     * the walk never reaches is appended rather than dropped.
     */
    public List<Edge> order(List<String> entryPoints) {
        Map<String, List<Edge>> bySource = groupBySource();
        List<Edge> ordered = new ArrayList<>();
        Set<String> visited = new HashSet<>();

        for (String entryPoint : entryPoints) {
            walk(entryPoint, bySource, visited, ordered);
        }

        appendUnreached(bySource, ordered);
        spliceTriggerEdges(ordered);

        return stamp(ordered);
    }

    /** Edges leaving each program unit, in lexical order. Trigger edges are held back. */
    private Map<String, List<Edge>> groupBySource() {
        Map<String, List<Edge>> bySource = new LinkedHashMap<>();
        for (Edge edge : edges) {
            if (edge.confidence() == Confidence.TRIGGER_INDUCED) {
                continue;
            }
            bySource.computeIfAbsent(edge.from(), k -> new ArrayList<>()).add(edge);
        }
        Comparator<Edge> lexical = Comparator
                .comparing((Edge e) -> e.line() == null ? Integer.MAX_VALUE : e.line())
                .thenComparing(e -> e.to() == null ? "" : e.to());
        bySource.values().forEach(list -> list.sort(lexical));
        return bySource;
    }

    private void walk(String unitId, Map<String, List<Edge>> bySource,
                      Set<String> visited, List<Edge> ordered) {
        if (!visited.add(unitId)) {
            return;
        }
        for (Edge edge : bySource.getOrDefault(unitId, List.of())) {
            ordered.add(edge);
            if (edge.type() == EdgeType.CALL) {
                walk(edge.to(), bySource, visited, ordered);
            }
        }
    }

    /**
     * Units the entry point never reaches still get their edges emitted. Dropping
     * them would make the IR quietly incomplete, which is the one thing the
     * design forbids.
     */
    private void appendUnreached(Map<String, List<Edge>> bySource, List<Edge> ordered) {
        Set<Edge> already = new LinkedHashSet<>(ordered);
        bySource.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .flatMap(entry -> entry.getValue().stream())
                .filter(edge -> !already.contains(edge))
                .forEach(ordered::add);
    }

    /**
     * Places each trigger-induced edge immediately after the write that fires it,
     * so stepping through shows the audit row appearing right after the INSERT
     * that caused it — which is the whole point of surfacing these.
     */
    private void spliceTriggerEdges(List<Edge> ordered) {
        List<Edge> triggerEdges = edges.stream()
                .filter(e -> e.confidence() == Confidence.TRIGGER_INDUCED)
                .toList();

        for (Edge triggerEdge : triggerEdges) {
            int at = -1;
            for (int i = 0; i < ordered.size(); i++) {
                Edge candidate = ordered.get(i);
                if (candidate.type() == EdgeType.WRITE
                        && candidate.confidence() != Confidence.TRIGGER_INDUCED
                        && triggerEdge.from().equals(candidate.to())) {
                    at = i + 1;
                    break;
                }
            }
            if (at < 0) {
                ordered.add(triggerEdge);
            } else {
                ordered.add(at, triggerEdge);
            }
        }
    }

    private List<Edge> stamp(List<Edge> ordered) {
        List<Edge> out = new ArrayList<>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            Edge edge = ordered.get(i);
            out.add(Edge.builder()
                    .id("e" + (i + 1))
                    .type(edge.type())
                    .op(edge.op())
                    .from(edge.from())
                    .to(edge.to())
                    .step(i + 1)
                    .line(edge.line())
                    .sqlId(edge.sqlId())
                    .confidence(edge.confidence())
                    .resolvedVia(edge.resolvedVia())
                    .reachability(edge.reachability())
                    .guard(edge.guard())
                    .rawText(edge.rawText())
                    .viaTrigger(edge.viaTrigger())
                    .provenance(edge.provenance())
                    .traceOrder(edge.traceOrder())
                    .build());
        }
        return out;
    }

    /** Program units nothing calls — the natural roots when no entry point is named. */
    public List<String> defaultEntryPoints() {
        return callGraph.roots();
    }
}
