package com.example.plsqlvisualizer.freshness;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.plsqlvisualizer.FixtureSchema;
import com.example.plsqlvisualizer.db.DictionaryClient;
import com.example.plsqlvisualizer.db.UnitKey;
import com.example.plsqlvisualizer.model.Ir;
import com.example.plsqlvisualizer.model.Meta;
import com.example.plsqlvisualizer.model.StaticSource;
import com.example.plsqlvisualizer.model.Unit;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Freshness is a correctness property here, not a nicety: an IR that claims to
 * be current while describing code that has since been recompiled will show a
 * mutation order that no longer happens (design.md §7).
 */
class StalenessCheckerTest {

    private StalenessReport check(Ir ir) throws Exception {
        FixtureSchema.requireDatabase();
        try (DictionaryClient client = DictionaryClient.connect(
                FixtureSchema.URL, FixtureSchema.USER, FixtureSchema.PASSWORD)) {
            return new StalenessChecker(client).check(ir);
        }
    }

    @Test
    @DisplayName("a freshly extracted IR reports fresh")
    void freshIrIsFresh() throws Exception {
        assertThat(check(FixtureSchema.ir()).isFresh()).isTrue();
    }

    @Test
    @DisplayName("a moved LAST_DDL_TIME marks that unit for re-extraction")
    void detectsRecompiledUnit() throws Exception {
        Ir ir = withUnitsRewritten(FixtureSchema.ir(),
                unit -> "PKG_VALIDATE".equals(unit.name())
                        ? new Unit(unit.owner(), unit.name(), unit.type(), Instant.EPOCH)
                        : unit);

        StalenessReport report = check(ir);

        assertThat(report.isFresh()).isFalse();
        assertThat(report.changed()).contains(UnitKey.of("PKG_VALIDATE", "PACKAGE BODY"));
        assertThat(report.needsReextraction())
                .as("only the changed unit needs rebuilding — the dictionary is per-object")
                .contains(UnitKey.of("PKG_VALIDATE", "PACKAGE BODY"));
        assertThat(report.summary()).contains("STALE");
    }

    @Test
    @DisplayName("a unit missing from the IR is reported as added")
    void detectsNewUnit() throws Exception {
        Ir ir = withUnits(FixtureSchema.ir(), units -> units.stream()
                .filter(u -> !"PKG_DYNAMIC".equals(u.name()))
                .toList());

        StalenessReport report = check(ir);

        assertThat(report.added()).contains(UnitKey.of("PKG_DYNAMIC", "PACKAGE BODY"));
    }

    @Test
    @DisplayName("a unit the schema no longer has is reported as removed")
    void detectsDroppedUnit() throws Exception {
        Ir ir = withUnits(FixtureSchema.ir(), units -> {
            List<Unit> extended = new ArrayList<>(units);
            extended.add(new Unit(units.get(0).owner(), "PKG_GONE", "PACKAGE BODY", Instant.EPOCH));
            return extended;
        });

        StalenessReport report = check(ir);

        assertThat(report.removed()).contains(UnitKey.of("PKG_GONE", "PACKAGE BODY"));
        assertThat(report.needsReextraction())
                .as("a dropped unit needs pruning, not re-extraction")
                .doesNotContain(UnitKey.of("PKG_GONE", "PACKAGE BODY"));
    }

    private Ir withUnitsRewritten(Ir ir, java.util.function.UnaryOperator<Unit> rewrite) {
        return withUnits(ir, units -> units.stream().map(rewrite).toList());
    }

    private Ir withUnits(Ir ir, java.util.function.UnaryOperator<List<Unit>> rewrite) {
        StaticSource source = ir.meta().staticSource();
        StaticSource rewritten = new StaticSource(
                source.generatedAt(), rewrite.apply(source.units()));
        Meta meta = new Meta(ir.meta().schemaVersion(), ir.meta().db(), ir.meta().entryPoint(),
                rewritten, ir.meta().traceSource());
        return new Ir(meta, ir.nodes(), ir.edges());
    }
}
