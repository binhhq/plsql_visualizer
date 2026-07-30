package com.example.plsqlvisualizer.freshness;

import com.example.plsqlvisualizer.db.DictionaryClient;
import com.example.plsqlvisualizer.db.ObjectRow;
import com.example.plsqlvisualizer.db.UnitKey;
import com.example.plsqlvisualizer.model.Ir;
import com.example.plsqlvisualizer.model.Unit;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The pull half of the freshness model (design.md §7).
 *
 * <p>Nothing runs the extractor automatically. Oracle refreshes the dictionary
 * on recompile, but our IR is a file that ages silently — and an IR that looks
 * current while describing last week's code is exactly the failure the design
 * is trying to prevent. So the IR records each unit's {@code LAST_DDL_TIME}, and
 * this compares it against the live schema.
 *
 * <p>Because the dictionary is per-object, re-extraction is naturally
 * incremental: only the units this reports need rebuilding.
 */
public class StalenessChecker {

    private final DictionaryClient client;

    public StalenessChecker(DictionaryClient client) {
        this.client = client;
    }

    public StalenessReport check(Ir ir) throws SQLException {
        Map<UnitKey, Instant> recorded = recordedUnits(ir);
        Map<UnitKey, Instant> live = new LinkedHashMap<>();
        for (ObjectRow row : client.objects()) {
            live.put(row.unit(), row.lastDdlTime());
        }

        List<UnitKey> changed = new ArrayList<>();
        List<UnitKey> added = new ArrayList<>();
        List<UnitKey> removed = new ArrayList<>();

        live.forEach((unit, lastDdl) -> {
            if (!recorded.containsKey(unit)) {
                added.add(unit);
            } else if (movedSince(recorded.get(unit), lastDdl)) {
                changed.add(unit);
            }
        });

        recorded.keySet().stream().filter(unit -> !live.containsKey(unit)).forEach(removed::add);

        return new StalenessReport(List.copyOf(changed), List.copyOf(added), List.copyOf(removed));
    }

    private Map<UnitKey, Instant> recordedUnits(Ir ir) {
        Map<UnitKey, Instant> recorded = new LinkedHashMap<>();
        if (ir.meta() == null || ir.meta().staticSource() == null
                || ir.meta().staticSource().units() == null) {
            return recorded;
        }
        for (Unit unit : ir.meta().staticSource().units()) {
            recorded.put(UnitKey.of(unit.name(), unit.type()), unit.lastDdlTime());
        }
        return recorded;
    }

    /**
     * A missing timestamp on either side counts as changed: we cannot prove the
     * unit is current, and claiming freshness we have not verified is the one
     * thing this check exists to avoid.
     */
    private boolean movedSince(Instant recorded, Instant live) {
        return recorded == null || live == null || !recorded.equals(live);
    }
}
