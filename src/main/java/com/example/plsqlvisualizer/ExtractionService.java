package com.example.plsqlvisualizer;

import com.example.plsqlvisualizer.config.VisualizerProperties;
import com.example.plsqlvisualizer.db.DictionaryClient;
import com.example.plsqlvisualizer.db.ObjectRow;
import com.example.plsqlvisualizer.db.UnitKey;
import com.example.plsqlvisualizer.extract.DictionarySnapshot;
import com.example.plsqlvisualizer.graph.IrBuilder;
import com.example.plsqlvisualizer.model.Ir;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
            return new IrBuilder(new DictionarySnapshot(client, restrictTo(client)))
                    .build(props.entry());
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
     * <p>Every trigger is added whatever was listed. Trigger writes arrive
     * through the same per-unit statement query as everything else, so leaving
     * them out of the restriction would silently drop the writes that appear in
     * no procedure's source — the one thing this tool exists to surface.
     */
    public Collection<UnitKey> restrictTo(DictionaryClient client) throws Exception {
        List<String> wanted = props.units();
        if (wanted.isEmpty()) {
            return null;
        }

        Set<UnitKey> units = new LinkedHashSet<>();
        Set<String> seenNames = new LinkedHashSet<>();
        int triggers = 0;
        for (ObjectRow row : client.objects()) {
            UnitKey unit = row.unit();
            if (unit.isTrigger()) {
                units.add(unit);
                triggers++;
            } else if (wanted.contains(unit.name().toUpperCase(Locale.ROOT))) {
                units.add(unit);
                seenNames.add(unit.name().toUpperCase(Locale.ROOT));
            }
        }

        List<String> missing = wanted.stream().filter(n -> !seenNames.contains(n)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "plsql.units names nothing in this schema: " + String.join(", ", missing));
        }

        System.out.printf("Reading %d requested unit(s) plus %d trigger(s); the rest of the"
                + " schema is not queried.%n", units.size() - triggers, triggers);
        return units;
    }
}
