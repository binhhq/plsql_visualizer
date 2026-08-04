package com.example.plsqlvisualizer;

import com.example.plsqlvisualizer.config.VisualizerProperties;
import com.example.plsqlvisualizer.db.DictionaryClient;
import com.example.plsqlvisualizer.db.UnitKey;
import com.example.plsqlvisualizer.extract.DictionarySnapshot;
import com.example.plsqlvisualizer.extract.ScopeResolver;
import com.example.plsqlvisualizer.graph.IrBuilder;
import com.example.plsqlvisualizer.model.Ir;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Builds an IR from the live schema. Shared by the run-once {@link IrRunner} and
 * the HTTP endpoints, so a served IR and a written one come off the same path —
 * a served graph that differed from the file would be worse than no server.
 */
@Service
public class ExtractionService {

    private final VisualizerProperties props;

    public ExtractionService(VisualizerProperties props) {
        this.props = props;
    }

    public Ir extract() throws Exception {
        try (DictionaryClient client = connect()) {
            Collection<UnitKey> units = restrictTo(client);
            DictionarySnapshot snapshot = units == null
                    ? new DictionarySnapshot(client, null)
                    : DictionarySnapshot.scoped(client, units);
            return new IrBuilder(snapshot).build(props.entry());
        }
    }

    /**
     * The graph reachable from one named unit — the on-demand path the search box
     * drives, and the only one that is tractable against a large schema.
     *
     * @param unitName package, procedure, function or trigger name, as typed
     * @param depth call-graph hops to follow; null uses {@code plsql.depth}
     */
    public Scoped extractScoped(String unitName, Integer depth) throws Exception {
        try (DictionaryClient client = connect()) {
            ScopeResolver.Scope scope = ScopeResolver.resolve(client, unitName,
                    depth == null ? props.depth() : depth, props.maxUnits());
            // No entry point: the seed is a unit, not a subprogram, so the walk
            // starts where it always does — at whatever nothing in scope calls.
            Ir ir = new IrBuilder(DictionarySnapshot.scoped(client, scope.units())).build(null);
            return new Scoped(ir, scope);
        }
    }

    /** A scoped IR together with what the scoping pass decided to read. */
    public record Scoped(Ir ir, ScopeResolver.Scope scope) {
    }

    /** Units matching what has been typed so far, for the search box. */
    public List<UnitKey> searchUnits(String needle, int limit) throws Exception {
        try (DictionaryClient client = connect()) {
            return client.searchUnits(needle, limit);
        }
    }

    public DictionaryClient connect() throws Exception {
        VisualizerProperties.Oracle db = props.oracle();
        return DictionaryClient.connect(db.url(), db.username(), db.password());
    }

    /**
     * Resolves {@code plsql.units} to the units the dictionary queries restrict
     * to, or null for the whole schema.
     *
     * <p>Triggers are pulled in by {@link ScopeResolver}, which adds the ones
     * standing on tables the listed units write. Trigger writes appear in no
     * procedure's source, so leaving them out entirely would hide exactly what
     * this tool exists to surface — but adding <em>every</em> trigger in the
     * schema, as this once did, makes the restriction almost worthless on a
     * database with thousands of them.
     */
    public Collection<UnitKey> restrictTo(DictionaryClient client) throws Exception {
        List<String> wanted = props.units();
        if (wanted.isEmpty()) {
            return null;
        }

        // ScopeResolver throws by name when a listed unit does not exist, which
        // says more than a collected "these were missing" ever did.
        Set<UnitKey> units = new LinkedHashSet<>();
        for (String name : wanted) {
            units.addAll(ScopeResolver.resolve(client, name, props.depth(), props.maxUnits())
                    .units());
        }

        System.out.printf("Reading %d unit(s) reachable from %s; the rest of the"
                + " schema is not queried.%n", units.size(), String.join(", ", wanted));
        return units;
    }
}
