package com.example.plsqlvisualizer.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        String sql = """
                SELECT s.object_name, s.object_type, s.usage_id, s.usage_context_id,
                       s.type, s.line, s.sql_id, i.name, i.type
                  FROM user_statements s
                  JOIN user_identifiers i
                    ON  i.object_name      = s.object_name
                    AND i.object_type      = s.object_type
                    AND i.usage_context_id = s.usage_id
                    AND i.usage            = 'REFERENCE'
                    AND i.type IN ('TABLE','VIEW','SYNONYM')
                 WHERE s.type IN %s
                 ORDER BY s.object_name, s.line
                """.formatted(DML_TYPES);
        return query(sql, rs -> new RawWrite(
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
        String sql = """
                SELECT c.object_name, c.object_type, c.usage_id, c.usage_context_id,
                       c.line, d.object_name, d.object_type, c.name
                  FROM user_identifiers c
                  JOIN user_identifiers d
                    ON  d.signature = c.signature
                    AND d.usage IN ('DECLARATION','DEFINITION')
                 WHERE c.usage = 'CALL'
                   AND d.object_type IN ('PACKAGE','PACKAGE BODY','PROCEDURE','FUNCTION')
                 ORDER BY c.object_name, c.line
                """;
        return query(sql, rs -> new RawCall(
                UnitKey.of(rs.getString(1), rs.getString(2)),
                rs.getLong(3), rs.getLong(4), rs.getInt(5),
                rs.getString(6), rs.getString(7), rs.getString(8)));
    }

    /** Every identifier in the schema, for walking the usage-context tree. */
    public List<IdentifierRow> identifiers() throws SQLException {
        String sql = """
                SELECT object_name, object_type, usage_id, usage_context_id,
                       name, type, usage, line, col, signature
                  FROM user_identifiers
                 WHERE object_type IN %s
                 ORDER BY object_name, object_type, usage_id
                """.formatted(ANALYSED_TYPES);
        return query(sql, rs -> new IdentifierRow(
                UnitKey.of(rs.getString(1), rs.getString(2)),
                rs.getLong(3), rs.getLong(4),
                rs.getString(5), rs.getString(6), rs.getString(7),
                rs.getInt(8), rs.getInt(9), rs.getString(10)));
    }

    /** Every statement, including SELECT and EXECUTE IMMEDIATE. */
    public List<StatementRow> statements() throws SQLException {
        String sql = """
                SELECT object_name, object_type, usage_id, usage_context_id,
                       type, line, col, sql_id
                  FROM user_statements
                 WHERE object_type IN %s
                 ORDER BY object_name, object_type, usage_id
                """.formatted(ANALYSED_TYPES);
        return query(sql, rs -> new StatementRow(
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
        String sql = """
                SELECT name, type, line, text
                  FROM user_source
                 WHERE type IN %s
                 ORDER BY name, type, line
                """.formatted(ANALYSED_TYPES);
        Map<UnitKey, List<String>> byUnit = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                UnitKey unit = UnitKey.of(rs.getString(1), rs.getString(2));
                int line = rs.getInt(3);
                String text = rs.getString(4);
                List<String> lines = byUnit.computeIfAbsent(unit, k -> new ArrayList<>());
                // USER_SOURCE is 1-based and may skip nothing, but pad defensively so
                // index i always holds line i+1.
                while (lines.size() < line - 1) {
                    lines.add("");
                }
                lines.add(text == null ? "" : stripLineBreak(text));
            }
        }
        return byUnit;
    }

    private static String stripLineBreak(String text) {
        return text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
    }

    /** Timestamp of this extraction run. */
    public Instant now() {
        return Instant.now();
    }

    private <T> List<T> query(String sql, RowMapper<T> mapper) throws SQLException {
        List<T> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
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
