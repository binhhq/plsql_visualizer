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

    /**
     * ORA-01795 caps an IN list at 1000 expressions. Stay well under it and
     * stitch the chunks together in Java — a large changeset must not be the
     * thing that breaks an incremental refresh.
     */
    private static final int UNIT_CHUNK = 400;

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
     *        the whole schema. The callee side of the join stays unrestricted, so
     *        a call into an unchanged package still resolves.
     */
    public List<RawCall> calls(Collection<UnitKey> units) throws SQLException {
        return byUnit(units, "c.object_name", "c.object_type", filter -> """
                SELECT c.object_name, c.object_type, c.usage_id, c.usage_context_id,
                       c.line, d.object_name, d.object_type, c.name
                  FROM user_identifiers c
                  JOIN user_identifiers d
                    ON  d.signature = c.signature
                    AND d.usage IN ('DECLARATION','DEFINITION')
                 WHERE c.usage = 'CALL'
                   AND d.object_type IN ('PACKAGE','PACKAGE BODY','PROCEDURE','FUNCTION')%s
                 ORDER BY c.object_name, c.line
                """.formatted(filter), rs -> new RawCall(
                UnitKey.of(rs.getString(1), rs.getString(2)),
                rs.getLong(3), rs.getLong(4), rs.getInt(5),
                rs.getString(6), rs.getString(7), rs.getString(8)));
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
        String sql = """
                SELECT synonym_name, table_owner, table_name
                  FROM all_synonyms
                 WHERE owner IN (USER, 'PUBLIC')
                """;
        return query(sql, rs -> new SynonymRow(rs.getString(1), rs.getString(2), rs.getString(3)));
    }

    /** Query E (first half) — which table fires which trigger. */
    public List<TriggerRow> triggers() throws SQLException {
        String sql = """
                SELECT trigger_name, table_name, triggering_event
                  FROM user_triggers
                """;
        return query(sql, rs -> new TriggerRow(rs.getString(1), rs.getString(2), rs.getString(3)));
    }

    /** Query F — {@code LAST_DDL_TIME} per unit, for the staleness check. */
    public List<ObjectRow> objects() throws SQLException {
        String sql = """
                SELECT object_name, object_type, last_ddl_time
                  FROM user_objects
                 WHERE object_type IN %s
                """.formatted(ANALYSED_TYPES);
        return query(sql, rs -> {
            Timestamp ts = rs.getTimestamp(3);
            return new ObjectRow(UnitKey.of(rs.getString(1), rs.getString(2)),
                    ts == null ? null : ts.toInstant());
        });
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
        for (List<UnitKey> chunk : chunks(units)) {
            String filter = "\n                   AND (%s, %s) IN (%s)".formatted(
                    nameColumn, typeColumn,
                    String.join(", ", Collections.nCopies(chunk.size(), "(?, ?)")));
            out.addAll(query(sql.apply(filter), chunk, mapper));
        }
        return out;
    }

    private static List<List<UnitKey>> chunks(Collection<UnitKey> units) {
        List<UnitKey> all = List.copyOf(units);
        List<List<UnitKey>> chunks = new ArrayList<>();
        for (int from = 0; from < all.size(); from += UNIT_CHUNK) {
            chunks.add(all.subList(from, Math.min(all.size(), from + UNIT_CHUNK)));
        }
        return chunks;
    }

    private <T> List<T> query(String sql, RowMapper<T> mapper) throws SQLException {
        return query(sql, List.of(), mapper);
    }

    /** Unit names come from the dictionary, but they are still bound, not interpolated. */
    private <T> List<T> query(String sql, List<UnitKey> binds, RowMapper<T> mapper)
            throws SQLException {
        List<T> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int parameter = 1;
            for (UnitKey unit : binds) {
                ps.setString(parameter++, unit.name());
                ps.setString(parameter++, unit.type());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapper.map(rs));
                }
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
