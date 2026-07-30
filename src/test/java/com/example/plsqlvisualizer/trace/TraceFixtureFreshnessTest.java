package com.example.plsqlvisualizer.trace;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.plsqlvisualizer.FixtureSchema;
import com.example.plsqlvisualizer.model.Edge;
import com.example.plsqlvisualizer.model.Ir;
import com.example.plsqlvisualizer.model.IrJson;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the committed IR fixture the trace tests merge against.
 *
 * <p>{@code TraceParserTest} and {@code TraceMergerTest} run off files so they need
 * no database — but that independence is exactly what lets the committed IR drift
 * away from the fixture schema without anyone noticing. A {@code sql_id} is a hash
 * of the statement text, so editing {@code docs/02_packages.sql} silently breaks
 * the join those tests rely on, and they would keep passing against a stale file.
 *
 * <p>This test is the tripwire. It needs Oracle, and skips without it.
 */
class TraceFixtureFreshnessTest {

    private static final Path STATIC_IR = Path.of("src/test/resources/ir-place-order.json");

    @Test
    @DisplayName("the committed IR fixture still matches what the extractor produces")
    void committedFixtureMatchesLiveExtraction() throws Exception {
        Ir live = FixtureSchema.ir();
        Ir committed = IrJson.read(STATIC_IR);

        assertThat(sqlIds(committed))
                .as("re-run: ./mvnw exec:java -Dexec.args=\"--entry PKG_ORDER.SUBMIT "
                        + "--out src/test/resources/ir-place-order.json\", then re-capture the "
                        + "traces with ./scripts/capture-trace.sh")
                .isEqualTo(sqlIds(live));
        assertThat(committed.edges()).hasSameSizeAs(live.edges());
        assertThat(committed.nodes()).containsExactlyInAnyOrderElementsOf(live.nodes());
    }

    private static List<String> sqlIds(Ir ir) {
        return ir.edges().stream().map(Edge::sqlId).filter(java.util.Objects::nonNull).sorted()
                .toList();
    }
}
