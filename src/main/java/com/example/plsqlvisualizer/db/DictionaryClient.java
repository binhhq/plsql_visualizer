package com.example.plsqlvisualizer.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Thin JDBC client over the PL/Scope data dictionary. It runs the reference
 * queries from {@code docs/05_extraction_queries.sql} and returns raw rows —
 * every interpretation happens in {@code extract/}.
 *
 * <p>Oracle already parsed the PL/SQL at compile time (design.md §2.1); this
 * class is deliberately the only place that talks to the database, and it does
 * nothing clever.
 *
 * <p>Uses the {@code USER_*} views, so the extractor must connect <em>as</em>
 * the schema being analysed.
 */
public class DictionaryClient implements AutoCloseable {

    /** Statement types that write data. Everything else (SELECT, …) yields no edge. */
    private static final String DML_TYPES = "('INSERT','UPDATE','DELETE','MERGE')";

    /** Units we analyse. Package specs hold no statements, so bodies only. */
    private static final String ANALYSED_TYPES = "('PACKAGE BODY','PROCEDURE','FUNCTION','TRIGGER')";

    /** Where a callee can live. A spec declares it; a body defines it. */
    private static final String CALLEE_TYPES = "('PACKAGE','PACKAGE BODY','PROCEDURE','FUNCTION')";

    /**
     * ORA-01795 caps an IN list at 1000 expressions. Stay well under it and
     * stitch the chunks together in Java — a large changeset must not be the
     * thing that breaks an incremental refresh.
     */
    private static final int UNIT_CHUNK = 400;

    /**
     * Rows per round trip. The Oracle thin driver defaults to 10, which on a
     * dictionary view with millions of rows is hundreds of thousands of network
     * round trips — the single largest reason a schema-wide read looks hung
     * rather than slow. Every query here streams a result set start to finish,
     * so a large prefetch is exactly the right shape.
     */
    private static final int FETCH_SIZE = 5_000;

    private final Connection connection;
    private final String schema;

    public DictionaryClient(Connection connection) throws SQLException {
        this.connection = connection;
        this.schema = queryCurrentSchema();
    }

    public static DictionaryClient connect(String url, String user, String password) throws SQLException {
        return new DictionaryClient(DriverManager.getConnection(url, user, password));
    }

    /** The schema we are connected as — it owns every object the USER_* views expose. */
    public String schema() {
        return schema;
    }

    private String queryCurrentSchema() throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT USER FROM dual");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getString(1);
        }
    }

    /** Query A — DML statements joined to the object sitting directly under them. */
    public List<RawWrite> writes() throws SQLException {
        return writes(null);
    }

    /** @param units restrict to these units; null means the whole schema */
    public List<RawWrite> writes(Collection<UnitKey> units) throws SQLException {
        return byUnit(units, "s.object_name", "s.object_type", filter -> """
                SELECT s.object_name, s.object_type, s.usage_id, s.usage_context_id,
                       s.type, s.line, s.sql_id, i.name, i.type
                  FROM user_statements s
                  JOIN user_identifiers i
                    ON  i.object_name      = s.object_name
                    AND i.object_type      = s.object_type
                    AND i.usage_context_id = s.usage_id
                    AND i.usage            = 'REFERENCE'
                    AND i.type IN ('TABLE','VIEW','SYNONYM')
                 WHERE s.type IN %s%s
                 ORDER BY s.object_name, s.line
                """.formatted(DML_TYPES, filter), rs -> new RawWrite(
                UnitKey.of(rs.getString(1), rs.getString(2)),
                rs.getLong(3), rs.getLong(4),
                rs.getString(5), rs.getInt(6), rs.getString(7),
                rs.getString(8), rs.getString(9)));
    }

    /**
     * Query B — CALL identifiers resolved to the callee's owning unit.
     *
     * <p>A call matches both the package spec and its body by signature, so the
     * result carries duplicates; {@code CallGraphExtractor} collapses them.
     */
    public List<RawCall> calls() throws SQLException {
        return calls(null);
    }

    /**
     * @param units restrict to calls made <em>from</em> these units; null means
     *        the whole schema.
     */
    public List<RawCall> calls(Collection<UnitKey> units) throws SQLException {
        if (units == null) {
            return byUnit(null, "c.object_name", "c.object_type", filter -> """
                    SELECT c.object_name, c.object_type, c.usage_id, c.usage_context_id,
                           c.line, d.object_name, d.object_type, c.name
                      FROM user_identifiers c
                      JOIN user_identifiers d
                        ON  d.signature = c.signature
                        AND d.usage IN ('DECLARATION','DEFINITION')
                     WHERE c.usage = 'CALL'
                       AND d.object_type IN %s%s
                     ORDER BY c.object_name, c.line
                    """.formatted(CALLEE_TYPES, filter), rs -> new RawCall(
                    UnitKey.of(rs.getString(1), rs.getString(2)),
                    rs.getLong(3), rs.getLong(4), rs.getInt(5),
                    rs.getString(6), rs.getString(7), rs.getString(8)));
        }
        if (units.isEmpty()) {
            return List.of();
        }

        // Two restricted queries rather than one self-join. Written as a join,
        // the callee side has no predicate at all — Oracle must hash the whole of
        // USER_IDENTIFIERS whatever we asked for, so narrowing the scope bought
        // nothing. Resolving the signatures separately keeps both halves bounded
        // by what was actually asked for, which is the point of scoping at all.
        List<PendingCall> pending = callsAwaitingResolution(units);
        Map<String, List<UnitKey>> declaring = declaringUnits(
                pending.stream().map(PendingCall::signature).distinct().toList());

        List<RawCall> out = new ArrayList<>();
        for (PendingCall call : pending) {
            // One RawCall per declaring unit, exactly as the join produced: a
            // callee matches both its package spec and its body, and collapsing
            // that pair is CallGraphExtractor's job, not ours.
            for (UnitKey callee : declaring.getOrDefault(call.signature(), List.of())) {
                out.add(new RawCall(call.callerUnit(), call.usageId(), call.contextId(),
                        call.line(), callee.name(), callee.type(), call.name()));
            }
        }
        return out;
    }

    /** A CALL identifier before its signature has been resolved to an owning unit. */
    private record PendingCall(UnitKey callerUnit, long usageId, long contextId,
                               int line, String name, String signature) {
    }

    private List<PendingCall> callsAwaitingResolution(Collection<UnitKey> units)
            throws SQLException {
        return byUnit(units, "object_name", "object_type", filter -> """
                SELECT object_name, object_type, usage_id, usage_context_id,
                       line, name, signature
                  FROM user_identifiers
                 WHERE usage = 'CALL'%s
                 ORDER BY object_name, line
                """.formatted(filter), rs -> new PendingCall(
                UnitKey.of(rs.getString(1), rs.getString(2)),
                rs.getLong(3), rs.getLong(4), rs.getInt(5),
                rs.getString(6), rs.getString(7)));
    }

    /**
     * Which unit declares or defines each of these signatures. {@code SIGNATURE}
     * is globally unique per subprogram, so this is the whole of cross-package
     * call resolution (design.md §4.1).
     */
    public Map<String, List<UnitKey>> declaringUnits(Collection<String> signatures)
            throws SQLException {
        Map<String, List<UnitKey>> out = new LinkedHashMap<>();
        for (List<String> chunk : chunks(List.copyOf(signatures))) {
            String sql = """
                    SELECT signature, object_name, object_type
                      FROM user_identifiers
                     WHERE usage IN ('DECLARATION','DEFINITION')
                       AND object_type IN %s
                       AND signature IN (%s)
                    """.formatted(CALLEE_TYPES, placeholders(chunk.size()));
            for (String[] row : queryStrings(sql, chunk,
                    rs -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)})) {
                out.computeIfAbsent(row[0], k -> new ArrayList<>())
                        .add(UnitKey.of(row[1], row[2]));
            }
        }
        return out;
    }

    /** Every identifier in the schema, for walking the usage-context tree. */
    public List<IdentifierRow> identifiers() throws SQLException {
        return identifiers(null);
    }

    /** @param units restrict to these units; null means the whole schema */
    public List<IdentifierRow> identifiers(Collection<UnitKey> units) throws SQLException {
        return byUnit(units, "object_name", "object_type", filter -> """
                SELECT object_name, object_type, usage_id, usage_context_id,
                       name, type, usage, line, col, signature
                  FROM user_identifiers
                 WHERE object_type IN %s%s
                 ORDER BY object_name, object_type, usage_id
                """.formatted(ANALYSED_TYPES, filter), rs -> new IdentifierRow(
                UnitKey.of(rs.getString(1), rs.getString(2)),
                rs.getLong(3), rs.getLong(4),
                rs.getString(5), rs.getString(6), rs.getString(7),
                rs.getInt(8), rs.getInt(9), rs.getString(10)));
    }

    /** Every statement, including SELECT and EXECUTE IMMEDIATE. */
    public List<StatementRow> statements() throws SQLException {
        return statements(null);
    }

    /** @param units restrict to these units; null means the whole schema */
    public List<StatementRow> statements(Collection<UnitKey> units) throws SQLException {
        return byUnit(units, "object_name", "object_type", filter -> """
                SELECT object_name, object_type, usage_id, usage_context_id,
                       type, line, col, sql_id
                  FROM user_statements
                 WHERE object_type IN %s%s
                 ORDER BY object_name, object_type, usage_id
                """.formatted(ANALYSED_TYPES, filter), rs -> new StatementRow(
                UnitKey.of(rs.getString(1), rs.getString(2)),
                rs.getLong(3), rs.getLong(4),
                rs.getString(5), rs.getInt(6), rs.getInt(7), rs.getString(8)));
    }

    /** Query C — synonyms visible to this schema (own + PUBLIC). */
    public List<SynonymRow> synonyms() throws SQLException {
        return synonyms(null);
    }

    /**
     * @param units restrict to synonyms these units actually name; null means
     *        every synonym visible to the schema. A production database routinely
     *        carries tens of thousands of PUBLIC synonyms, none of which matter to
     *        a graph of four packages.
     */
    public List<SynonymRow> synonyms(Collection<UnitKey> units) throws SQLException {
        if (units != null && units.isEmpty()) {
            return List.of();
        }
        String restriction = units == null ? "" : """
                   AND synonym_name IN (SELECT name
                                          FROM user_identifiers
                                         WHERE type = 'SYNONYM'%s)
                """;
        return byUnit(units, "object_name", "object_type", filter -> """
                SELECT synonym_name, table_owner, table_name
                  FROM all_synonyms
                 WHERE owner IN (USER, 'PUBLIC')
                %s
                """.formatted(restriction.isEmpty() ? "" : restriction.formatted(filter)),
                rs -> new SynonymRow(rs.getString(1), rs.getString(2), rs.getString(3)));
    }

    /** Query E (first half) — which table fires which trigger. */
    public List<TriggerRow> triggers() throws SQLException {
        String sql = """
                SELECT trigger_name, table_name, triggering_event
                  FROM user_triggers
                """;
        return query(sql, rs -> new TriggerRow(rs.getString(1), rs.getString(2), rs.getString(3)));
    }

    /**
     * The named triggers, with the table each stands on. What a scoped extraction
     * needs: it already knows which triggers are in play, and only has to learn
     * which table firing them is what fires them.
     */
    public List<TriggerRow> triggersNamed(Collection<String> names) throws SQLException {
        if (names.isEmpty()) {
            return List.of();
        }
        List<TriggerRow> out = new ArrayList<>();
        for (List<String> chunk : chunks(List.copyOf(names))) {
            String sql = """
                    SELECT trigger_name, table_name, triggering_event
                      FROM user_triggers
                     WHERE trigger_name IN (%s)
                    """.formatted(placeholders(chunk.size()));
            out.addAll(queryStrings(sql, chunk, rs -> new TriggerRow(
                    rs.getString(1), rs.getString(2), rs.getString(3))));
        }
        return out;
    }

    /**
     * Triggers firing on any of these tables — the entry point to the writes that
     * appear in no procedure's own source (design.md §4.5).
     *
     * <p>Scoped extraction cannot simply take every trigger in the schema: on a
     * large database that is thousands of units whose source and identifiers then
     * have to be read, which defeats the scoping entirely. Only the triggers on
     * tables the scope actually writes can contribute an edge to it.
     */
    public List<TriggerRow> triggersOn(Collection<String> tableNames) throws SQLException {
        if (tableNames.isEmpty()) {
            return List.of();
        }
        List<TriggerRow> out = new ArrayList<>();
        for (List<String> chunk : chunks(List.copyOf(tableNames))) {
            String sql = """
                    SELECT trigger_name, table_name, triggering_event
                      FROM user_triggers
                     WHERE table_name IN (%s)
                    """.formatted(placeholders(chunk.size()));
            out.addAll(queryStrings(sql, chunk, rs -> new TriggerRow(
                    rs.getString(1), rs.getString(2), rs.getString(3))));
        }
        return out;
    }

    /** Query F — {@code LAST_DDL_TIME} per unit, for the staleness check. */
    public List<ObjectRow> objects() throws SQLException {
        return objects(null);
    }

    /**
     * @param units restrict to these units; null means every analysable object in
     *        the schema. The unrestricted form is what the staleness check needs —
     *        it has to see units the IR does not know about yet — while a scoped
     *        extraction wants only the units it built from, so that
     *        {@code meta.static_source.units} stays a record of this graph rather
     *        than a hundred thousand names the graph never mentions.
     */
    public List<ObjectRow> objects(Collection<UnitKey> units) throws SQLException {
        return byUnit(units, "object_name", "object_type", filter -> """
                SELECT object_name, object_type, last_ddl_time
                  FROM user_objects
                 WHERE object_type IN %s%s
                """.formatted(ANALYSED_TYPES, filter), rs -> {
            Timestamp ts = rs.getTimestamp(3);
            return new ObjectRow(UnitKey.of(rs.getString(1), rs.getString(2)),
                    ts == null ? null : ts.toInstant());
        });
    }

    // ----------------------------------------------------------------- scoping

    /**
     * Analysable units whose name contains {@code needle}, for the search box.
     *
     * <p>Deliberately hits {@code USER_OBJECTS} and nothing else: it is small,
     * indexed on name, and answering "what can I look at?" must stay instant on a
     * schema where reading any one unit's identifiers is not.
     */
    public List<UnitKey> searchUnits(String needle, int limit) throws SQLException {
        String sql = """
                SELECT object_name, object_type
                  FROM (SELECT object_name, object_type
                          FROM user_objects
                         WHERE object_type IN %s
                           AND object_name LIKE '%%' || UPPER(?) || '%%' ESCAPE '\\'
                         ORDER BY CASE WHEN object_name LIKE UPPER(?) || '%%' THEN 0 ELSE 1 END,
                                  object_name, object_type)
                 WHERE ROWNUM <= %d
                """.formatted(ANALYSED_TYPES, Math.max(1, limit));
        String escaped = needle.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return queryStrings(sql, List.of(escaped, escaped),
                rs -> UnitKey.of(rs.getString(1), rs.getString(2)));
    }

    /** Every analysable unit carrying one of these names — a package brings its body. */
    public List<UnitKey> unitsNamed(Collection<String> names) throws SQLException {
        if (names.isEmpty()) {
            return List.of();
        }
        List<UnitKey> out = new ArrayList<>();
        for (List<String> chunk : chunks(List.copyOf(names))) {
            String sql = """
                    SELECT object_name, object_type
                      FROM user_objects
                     WHERE object_type IN %s
                       AND object_name IN (%s)
                    """.formatted(ANALYSED_TYPES, placeholders(chunk.size()));
            out.addAll(queryStrings(sql, chunk,
                    rs -> UnitKey.of(rs.getString(1), rs.getString(2))));
        }
        return out;
    }

    /**
     * Names of the objects these units write to, synonyms included and
     * unresolved. Feeds the trigger lookup, which only needs to know which tables
     * are touched — not what the edges eventually look like.
     */
    public List<String> writeTargetNames(Collection<UnitKey> units) throws SQLException {
        return byUnit(units, "s.object_name", "s.object_type", filter -> """
                SELECT DISTINCT i.name
                  FROM user_statements s
                  JOIN user_identifiers i
                    ON  i.object_name      = s.object_name
                    AND i.object_type      = s.object_type
                    AND i.usage_context_id = s.usage_id
                    AND i.usage            = 'REFERENCE'
                    AND i.type IN ('TABLE','VIEW','SYNONYM')
                 WHERE s.type IN %s%s
                """.formatted(DML_TYPES, filter), rs -> rs.getString(1));
    }

    /**
     * Source text per unit, keyed by unit and indexed by 1-based line.
     *
     * <p>PL/Scope records <em>where</em> statements are but not the control
     * structures around them — an {@code IF} creates no context row. The
     * reachability pass reads the source back to recover branch guards
     * (design.md §6 {@code guard}).
     */
    public Map<UnitKey, List<String>> source() throws SQLException {
        return source(null);
    }

    /** @param units restrict to these units; null means the whole schema */
    public Map<UnitKey, List<String>> source(Collection<UnitKey> units) throws SQLException {
        List<SourceLine> rows = byUnit(units, "name", "type", filter -> """
                SELECT name, type, line, text
                  FROM user_source
                 WHERE type IN %s%s
                 ORDER BY name, type, line
                """.formatted(ANALYSED_TYPES, filter), rs -> new SourceLine(
                UnitKey.of(rs.getString(1), rs.getString(2)),
                rs.getInt(3), rs.getString(4)));

        Map<UnitKey, List<String>> byUnit = new LinkedHashMap<>();
        for (SourceLine row : rows) {
            List<String> lines = byUnit.computeIfAbsent(row.unit(), k -> new ArrayList<>());
            // USER_SOURCE is 1-based and may skip nothing, but pad defensively so
            // index i always holds line i+1.
            while (lines.size() < row.line() - 1) {
                lines.add("");
            }
            lines.add(row.text() == null ? "" : stripLineBreak(row.text()));
        }
        return byUnit;
    }

    /** One raw {@code USER_SOURCE} row, before it is grouped into per-unit text. */
    private record SourceLine(UnitKey unit, int line, String text) {
    }

    private static String stripLineBreak(String text) {
        return text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
    }

    /** Timestamp of this extraction run. */
    public Instant now() {
        return Instant.now();
    }

    /**
     * Runs a per-unit query, optionally narrowed to a set of units — the query
     * half of the incremental refresh (design.md §7). The dictionary is keyed per
     * object, so restricting the scan is all "re-extract only what changed" takes.
     *
     * @param sql receives the extra predicate to splice in ahead of {@code ORDER BY};
     *        it is empty when no filter applies
     */
    private <T> List<T> byUnit(Collection<UnitKey> units, String nameColumn, String typeColumn,
                               UnaryOperator<String> sql, RowMapper<T> mapper) throws SQLException {
        if (units == null) {
            return query(sql.apply(""), List.of(), mapper);
        }
        // An empty restriction means "no units", never "all of them". A refresh
        // that only has units to prune asks for exactly this, and answering it
        // with the whole schema would duplicate every edge it kept.
        if (units.isEmpty()) {
            return List.of();
        }
        List<T> out = new ArrayList<>();
        // A unit pair costs two bind variables, so the chunk size halves against
        // the same ORA-01795 ceiling.
        for (List<UnitKey> chunk : chunks(List.copyOf(units))) {
            String filter = "\n                   AND (%s, %s) IN (%s)".formatted(
                    nameColumn, typeColumn,
                    String.join(", ", Collections.nCopies(chunk.size(), "(?, ?)")));
            out.addAll(query(sql.apply(filter), chunk, mapper));
        }
        return out;
    }

    /** Splits an IN-list into chunks Oracle will accept. */
    private static <T> List<List<T>> chunks(List<T> all) {
        List<List<T>> chunks = new ArrayList<>();
        for (int from = 0; from < all.size(); from += UNIT_CHUNK) {
            chunks.add(all.subList(from, Math.min(all.size(), from + UNIT_CHUNK)));
        }
        return chunks;
    }

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    private <T> List<T> query(String sql, RowMapper<T> mapper) throws SQLException {
        return query(sql, List.of(), mapper);
    }

    /** Unit names come from the dictionary, but they are still bound, not interpolated. */
    private <T> List<T> query(String sql, List<UnitKey> binds, RowMapper<T> mapper)
            throws SQLException {
        List<T> out = new ArrayList<>();
        try (PreparedStatement ps = prepare(sql)) {
            int parameter = 1;
            for (UnitKey unit : binds) {
                ps.setString(parameter++, unit.name());
                ps.setString(parameter++, unit.type());
            }
            out.addAll(drain(ps, mapper));
        }
        return out;
    }

    /** The same, for queries whose IN-list is plain strings rather than unit pairs. */
    private <T> List<T> queryStrings(String sql, List<String> binds, RowMapper<T> mapper)
            throws SQLException {
        try (PreparedStatement ps = prepare(sql)) {
            int parameter = 1;
            for (String bind : binds) {
                ps.setString(parameter++, bind);
            }
            return drain(ps, mapper);
        }
    }

    private PreparedStatement prepare(String sql) throws SQLException {
        PreparedStatement ps = connection.prepareStatement(sql);
        ps.setFetchSize(FETCH_SIZE);
        return ps;
    }

    private <T> List<T> drain(PreparedStatement ps, RowMapper<T> mapper) throws SQLException {
        List<T> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(mapper.map(rs));
            }
        }
        return out;
    }

    @FunctionalInterface
    private interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
