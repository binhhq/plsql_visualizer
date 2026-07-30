package com.example.plsqlvisualizer.extract;

import com.example.plsqlvisualizer.db.SynonymRow;
import com.example.plsqlvisualizer.model.Node;
import com.example.plsqlvisualizer.model.ResolvedVia;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the object named by a DML statement into the node it should point at
 * (design.md §4.3).
 *
 * <ul>
 *   <li>{@code TABLE} → itself, {@code resolved_via = direct}</li>
 *   <li>{@code SYNONYM} → its base object, {@code resolved_via = synonym}</li>
 *   <li>{@code VIEW} → kept as the view, {@code resolved_via = view}; writing
 *       through a view is a separate hop we flag rather than chase blindly</li>
 * </ul>
 *
 * <p>A synonym that resolves to nothing is <em>not</em> guessed at — the caller
 * turns that into a {@code dynamic-unknown} edge.
 */
public class SynonymResolver {

    /** The object a DML target denotes, once indirection is followed. */
    public record Target(String nodeId, String owner, String name, ResolvedVia via) {
    }

    private final Map<String, SynonymRow> bySynonymName = new HashMap<>();
    private final String defaultOwner;

    public SynonymResolver(List<SynonymRow> synonyms, String defaultOwner) {
        this.defaultOwner = defaultOwner;
        for (SynonymRow row : synonyms) {
            // The schema's own synonym shadows a PUBLIC one of the same name, and
            // USER rows are queried first, so keep the first seen.
            bySynonymName.putIfAbsent(row.synonymName(), row);
        }
    }

    /** Returns the resolved target, or null when it cannot be resolved honestly. */
    public Target resolve(String objectName, String objectKind) {
        return switch (objectKind) {
            case "TABLE" -> new Target(Node.tableId(defaultOwner, objectName),
                    defaultOwner, objectName, ResolvedVia.DIRECT);
            case "VIEW" -> new Target(Node.tableId(defaultOwner, objectName),
                    defaultOwner, objectName, ResolvedVia.VIEW);
            case "SYNONYM" -> resolveSynonym(objectName);
            default -> null;
        };
    }

    private Target resolveSynonym(String synonymName) {
        SynonymRow row = bySynonymName.get(synonymName);
        if (row == null || row.tableName() == null) {
            return null;
        }
        String owner = row.tableOwner() == null ? defaultOwner : row.tableOwner();
        return new Target(Node.tableId(owner, row.tableName()), owner, row.tableName(),
                ResolvedVia.SYNONYM);
    }
}
