package com.example.plsqlvisualizer.freshness;

import com.example.plsqlvisualizer.db.UnitKey;
import com.example.plsqlvisualizer.model.Edge;
import com.example.plsqlvisualizer.model.Ir;
import com.example.plsqlvisualizer.model.Node;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Swaps the stale units' edges out of an existing IR and the freshly extracted
 * ones in — the splice half of design.md §7. No database access: everything it
 * needs about the old graph is in the old IR.
 *
 * <p>It deliberately does not order the result. Step ordinals are positions in a
 * walk that crosses unit boundaries, so replacing one unit's edges renumbers the
 * whole graph; {@code IrBuilder.assemble} does that pass over the merged
 * population.
 */
public class IrSplicer {

    private final Ir previous;
    private final EdgeOwner owner;

    public IrSplicer(Ir previous) {
        this.previous = previous;
        this.owner = new EdgeOwner(previous.nodes());
    }

    /** Nodes and edges of the merged graph, unordered and unstamped. */
    public record Merged(List<Node> nodes, List<Edge> edges, int kept, int replaced) {
    }

    /**
     * Edges in the previous IR whose owning unit cannot be determined.
     *
     * <p>Non-empty means an incremental splice is not safe and the caller should
     * extract the whole schema instead. Callers are expected to check this
     * <em>before</em> narrowing a snapshot, since the decision changes what has to
     * be read from the database.
     */
    public List<Edge> unownedEdges() {
        return edgesOf(previous).stream().filter(edge -> owner.ownerOf(edge) == null).toList();
    }

    /**
     * @param staleUnits units whose old edges must go: changed, added and removed
     *        alike. A removed unit contributes no fresh edges, so naming it here is
     *        what prunes it.
     * @param freshNodes nodes from re-extracting the changed and added units
     * @param freshEdges edges from re-extracting the changed and added units
     */
    public Merged merge(Collection<UnitKey> staleUnits, List<Node> freshNodes,
                        List<Edge> freshEdges) {
        Set<UnitKey> stale = new HashSet<>(staleUnits);

        List<Edge> edges = new ArrayList<>();
        int kept = 0;
        for (Edge edge : edgesOf(previous)) {
            if (!stale.contains(owner.ownerOf(edge))) {
                edges.add(edge);
                kept++;
            }
        }
        edges.addAll(freshEdges);

        return new Merged(mergeNodes(freshNodes, edges), edges, kept, freshEdges.size());
    }

    /**
     * Previous nodes keep their position and newly discovered ones are appended, so
     * an unchanged graph re-serialises identically and a refresh shows up as a small
     * diff. Nodes no surviving edge touches are dropped — a table that only the
     * deleted unit wrote is no longer part of the graph.
     */
    private List<Node> mergeNodes(List<Node> freshNodes, List<Edge> edges) {
        Map<String, Node> byId = new LinkedHashMap<>();
        if (previous.nodes() != null) {
            previous.nodes().forEach(node -> byId.put(node.id(), node));
        }
        if (freshNodes != null) {
            freshNodes.forEach(node -> byId.putIfAbsent(node.id(), node));
        }

        Set<String> referenced = new HashSet<>();
        edges.forEach(edge -> {
            referenced.add(edge.from());
            referenced.add(edge.to());
        });

        return byId.values().stream().filter(node -> referenced.contains(node.id())).toList();
    }

    private static List<Edge> edgesOf(Ir ir) {
        return ir.edges() == null ? List.of() : ir.edges();
    }
}
