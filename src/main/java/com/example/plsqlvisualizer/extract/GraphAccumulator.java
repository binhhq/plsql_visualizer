package com.example.plsqlvisualizer.extract;

import com.example.plsqlvisualizer.model.Edge;
import com.example.plsqlvisualizer.model.Node;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects what the extractors find. Nodes are deduplicated by id — several
 * procedures writing the same table must converge on one vertex — while edges
 * are kept in discovery order and given their ids and step ordinals later, by
 * {@code IrBuilder}.
 */
public class GraphAccumulator {

    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final List<Edge> edges = new ArrayList<>();

    /** Registers a node, keeping the first definition seen for an id. */
    public Node node(Node node) {
        return nodes.putIfAbsent(node.id(), node) == null ? node : nodes.get(node.id());
    }

    public void edge(Edge edge) {
        edges.add(edge);
    }

    public List<Node> nodes() {
        return List.copyOf(nodes.values());
    }

    public List<Edge> edges() {
        return List.copyOf(edges);
    }
}
