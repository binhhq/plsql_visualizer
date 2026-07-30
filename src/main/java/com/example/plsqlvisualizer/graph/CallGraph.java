package com.example.plsqlvisualizer.graph;

import com.example.plsqlvisualizer.model.Edge;
import com.example.plsqlvisualizer.model.EdgeType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.jgrapht.Graph;
import org.jgrapht.alg.cycle.CycleDetector;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;

/**
 * The call graph as a JGraphT directed graph. PL/SQL packages call each other
 * freely, so this is a general digraph and not a DAG — {@code PKG_ORDER} and
 * {@code PKG_POSITION} in the fixtures call each other on purpose.
 *
 * <p>Used for two things: finding the roots to start stepping from, and
 * reporting cycles so a reader is told the order is circular rather than
 * silently seeing one arbitrary linearisation.
 */
public class CallGraph {

    private final Graph<String, DefaultEdge> graph =
            new DefaultDirectedGraph<>(DefaultEdge.class);

    public CallGraph(List<Edge> edges) {
        for (Edge edge : edges) {
            if (edge.type() != EdgeType.CALL) {
                continue;
            }
            graph.addVertex(edge.from());
            graph.addVertex(edge.to());
            if (!edge.from().equals(edge.to())) {
                graph.addEdge(edge.from(), edge.to());
            }
        }
    }

    /**
     * Units with no incoming call. These are where a walk should start.
     *
     * <p>If every unit is called — possible when the only units present sit
     * inside a cycle — the graph has no root, and the caller must name an entry
     * point explicitly.
     */
    public List<String> roots() {
        List<String> roots = new ArrayList<>();
        for (String vertex : graph.vertexSet()) {
            if (graph.inDegreeOf(vertex) == 0) {
                roots.add(vertex);
            }
        }
        roots.sort(Comparator.naturalOrder());
        return roots;
    }

    /** Units that participate in a call cycle. */
    public Set<String> unitsInCycles() {
        return new CycleDetector<>(graph).findCycles();
    }

    public boolean hasVertex(String unitId) {
        return graph.containsVertex(unitId);
    }
}
