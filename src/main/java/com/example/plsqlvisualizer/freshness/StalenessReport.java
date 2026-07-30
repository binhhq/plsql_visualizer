package com.example.plsqlvisualizer.freshness;

import com.example.plsqlvisualizer.db.UnitKey;
import java.util.List;

/**
 * What changed in the database since an IR was generated.
 *
 * @param changed units whose {@code LAST_DDL_TIME} moved — their edges are stale
 * @param added units that did not exist when the IR was built
 * @param removed units the IR knows about that are gone from the schema
 */
public record StalenessReport(List<UnitKey> changed, List<UnitKey> added, List<UnitKey> removed) {

    public boolean isFresh() {
        return changed.isEmpty() && added.isEmpty() && removed.isEmpty();
    }

    /** Units worth re-extracting. Removed units need pruning, not re-extraction. */
    public List<UnitKey> needsReextraction() {
        return java.util.stream.Stream.concat(changed.stream(), added.stream()).toList();
    }

    public String summary() {
        if (isFresh()) {
            return "static: fresh";
        }
        return "static: STALE — %d changed, %d added, %d removed"
                .formatted(changed.size(), added.size(), removed.size());
    }
}
