package com.example.plsqlvisualizer.freshness;

import com.example.plsqlvisualizer.db.UnitKey;
import com.example.plsqlvisualizer.model.Edge;
import com.example.plsqlvisualizer.model.Node;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which library unit produced an edge — the key an incremental refresh replaces by.
 *
 * <p>This is derivable from the IR alone, and that is the whole reason a refresh
 * can rebuild one unit without re-reading the schema:
 * <ul>
 *   <li>a write or a call leaves a {@code PROC:} node, and that node carries the
 *       unit name and unit type the edge was extracted from;</li>
 *   <li>a trigger-induced write leaves a <em>table</em> — the node cannot say who
 *       wrote it, which is exactly why those edges exist — so {@code via_trigger}
 *       names the owning trigger instead.</li>
 * </ul>
 *
 * <p>An edge whose owner cannot be determined is not guessed at. The caller is
 * expected to fall back to a full extraction, because keeping an edge that the
 * rebuild also emits would duplicate it, and dropping one that the rebuild does
 * not emit would lose a write silently — both are worse than re-reading the schema.
 */
final class EdgeOwner {

    private final Map<String, Node> nodesById = new LinkedHashMap<>();

    EdgeOwner(List<Node> nodes) {
        if (nodes != null) {
            nodes.forEach(node -> nodesById.put(node.id(), node));
        }
    }

    /** The unit whose re-extraction regenerates this edge, or null if undeterminable. */
    UnitKey ownerOf(Edge edge) {
        if (edge.viaTrigger() != null) {
            return UnitKey.of(edge.viaTrigger(), "TRIGGER");
        }
        Node from = nodesById.get(edge.from());
        if (from == null || from.unit() == null || from.unitType() == null) {
            return null;
        }
        return UnitKey.of(from.unit(), from.unitType());
    }
}
