package com.example.plsqlvisualizer.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Everything the extractor needs to decide what to do, bound from
 * {@code application.yaml} and overridable per run with {@code --plsql.*} on the
 * command line.
 *
 * <p>{@link #out()} deliberately has no default. The task decides where output
 * lands when nothing is configured: an extraction writes a new file, while a
 * refresh or a trace overlay rewrites its input in place. A blanket default
 * would silently turn the in-place tasks into copies.
 */
@ConfigurationProperties("plsql")
public record VisualizerProperties(

        @DefaultValue Oracle oracle,

        /** What to do on startup. */
        @DefaultValue("extract") Task task,

        /**
         * UNIT.SUBPROGRAM to walk from; null walks every uncalled unit.
         *
         * <p>This prunes the finished graph, not the dictionary reads — see
         * {@link #units()} for the setting that makes a large schema tractable.
         */
        String entry,

        /**
         * Library units to read from the dictionary. Empty means the whole
         * schema, which on a large one means USER_IDENTIFIERS and USER_STATEMENTS
         * are scanned end to end.
         *
         * <p>Names only — every object type carrying that name is included, so
         * a package brings its spec and its body. Triggers are always added on
         * top, whatever is listed: their writes reach the graph through the same
         * per-unit query, and dropping them would hide exactly the writes that
         * appear in nobody's source.
         */
        @DefaultValue List<String> units,

        /** Where to write. Null means "let the task choose" — see the class note. */
        Path out,

        /** The existing IR that CHECK, REFRESH and TRACE read. */
        Path ir,

        /** The 10046 trace file TRACE overlays onto {@link #ir()}. */
        Path traceFile,

        /** Name recorded in meta.trace_source; null uses the trace filename. */
        String scenario) {

    /**
     * Blank means absent. A YAML key written with no value — {@code scenario:} —
     * binds to an empty string rather than null, as does {@code --plsql.entry=}
     * on the command line, and an empty string would otherwise be taken as a
     * real entry point or a real scenario name and silence the fallback.
     */
    public VisualizerProperties {
        entry = blankToNull(entry);
        scenario = blankToNull(scenario);
        units = units == null ? List.of()
                : units.stream()
                        .map(u -> u == null ? "" : u.trim().toUpperCase(Locale.ROOT))
                        .filter(u -> !u.isEmpty())
                        .distinct()
                        .toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public enum Task {
        /** Read the schema and write a fresh IR. */
        EXTRACT,
        /** Report whether an existing IR still matches the schema. */
        CHECK,
        /** Re-read only what changed and splice it into an existing IR. */
        REFRESH,
        /** Overlay a 10046 trace onto an existing IR. Needs no database. */
        TRACE
    }

    /**
     * The schema to analyse, and the credentials to read it with — the extractor
     * connects as the schema it inspects, because the queries use USER_* views.
     */
    public record Oracle(
            @DefaultValue("jdbc:oracle:thin:@//localhost:1521/FREEPDB1") String url,
            @DefaultValue("tscope_test") String username,
            @DefaultValue("tscope") String password) {
    }
}
