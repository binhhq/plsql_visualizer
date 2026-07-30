package com.example.plsqlvisualizer.trace;

import com.example.plsqlvisualizer.model.Op;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads an event-10046 trace file into execution events (design.md §5).
 *
 * <p>Streaming, one line at a time, and it keeps only what a cursor needs to be
 * identified. A trace of one fixture procedure is already 800 KB — 16 800 lines,
 * of which about a hundred are the application's — so a real scenario on a real
 * schema is not something to hold in memory.
 *
 * <p>Only two line shapes matter:
 * <pre>
 * PARSING IN CURSOR #140 len=61 dep=1 uid=136 oct=2 ... sqlid='0f90zx1jvsgas'
 * INSERT INTO ORDER_LOG_202607 (order_id, note) VALUES (:1, :2)
 * END OF STMT
 * EXEC #140:c=203,e=203,p=0,cr=0,cu=0,mis=1,r=1,dep=1,og=1,plh=0,tim=1785406173806571
 * </pre>
 * The header declares a cursor and its text; the {@code EXEC} says it ran. Cursor
 * numbers are reused within a file, so a header always replaces what that number
 * meant before — and the text has to be captured at parse time, because by the
 * time the {@code EXEC} arrives the sections have interleaved with other cursors'.
 */
public class TraceParser {

    private static final Pattern CURSOR = Pattern.compile(
            "^PARSING IN CURSOR #(\\d+) len=\\d+ dep=(\\d+).*?sqlid='([^']+)'");

    private static final Pattern EXEC = Pattern.compile(
            "^EXEC #(\\d+):.*?,r=(\\d+),dep=(\\d+).*?,tim=(\\d+)");

    /**
     * The DML verb and its target object, optionally {@code OWNER.TABLE}.
     *
     * <p>The owner part is only taken when an actual dot follows. Without that
     * requirement {@code update seq$ set increment$=:2} reads as owner
     * {@code seq$} and table {@code set} — which then sails past every filter
     * that looks for Oracle's {@code $} naming convention.
     */
    private static final Pattern DML = Pattern.compile(
            "^\\s*(INSERT\\s+INTO|UPDATE|DELETE\\s+FROM|MERGE\\s+INTO)\\s+"
                    + "\"?([A-Z0-9_$#]+)\"?(?:\\s*\\.\\s*\"?([A-Z0-9_$#]+)\"?)?",
            Pattern.CASE_INSENSITIVE);

    /** Lines that close a statement's text block rather than belonging to it. */
    private static final List<String> NOT_SQL_TEXT = List.of(
            "END OF STMT", "PARSE #", "EXEC #", "FETCH #", "CLOSE #", "BINDS #",
            "STAT #", "WAIT #", "=====", "*** ", "CLOSE ", "ERROR #", "XCTEND");

    /** A cursor's identity while the file is being read. */
    private record Cursor(String sqlId, int depth, StringBuilder sql) {
    }

    public List<TraceEvent> parse(Path traceFile) throws IOException {
        Map<String, Cursor> cursors = new HashMap<>();
        List<TraceEvent> events = new ArrayList<>();
        Cursor collecting = null;

        try (BufferedReader reader = Files.newBufferedReader(traceFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher header = CURSOR.matcher(line);
                if (header.find()) {
                    collecting = new Cursor(header.group(3), Integer.parseInt(header.group(2)),
                            new StringBuilder());
                    cursors.put(header.group(1), collecting);
                    continue;
                }

                if (collecting != null) {
                    if (isStatementText(line)) {
                        appendText(collecting.sql(), line);
                        continue;
                    }
                    collecting = null;
                }

                Matcher exec = EXEC.matcher(line);
                if (exec.find()) {
                    Cursor cursor = cursors.get(exec.group(1));
                    if (cursor != null) {
                        events.add(toEvent(cursor, exec));
                    }
                }
            }
        }
        return events;
    }

    private TraceEvent toEvent(Cursor cursor, Matcher exec) {
        String sql = cursor.sql().toString().trim();
        Matcher dml = DML.matcher(sql);
        Op op = null;
        String target = null;
        if (dml.find()) {
            op = Op.fromStatementType(verbOf(dml.group(1)));
            // "OWNER.TABLE" or bare "TABLE" — group 3 is empty in the bare case.
            target = dml.group(3) == null || dml.group(3).isEmpty()
                    ? dml.group(2).toUpperCase(Locale.ROOT)
                    : dml.group(3).toUpperCase(Locale.ROOT);
        }

        return new TraceEvent(cursor.sqlId(),
                Integer.parseInt(exec.group(3)),
                Long.parseLong(exec.group(4)),
                Integer.parseInt(exec.group(2)),
                sql, op, target);
    }

    /** {@code INSERT INTO} → {@code INSERT}, {@code DELETE FROM} → {@code DELETE}. */
    private static String verbOf(String matched) {
        return matched.trim().split("\\s+")[0];
    }

    private static boolean isStatementText(String line) {
        if (line.isBlank()) {
            return false;
        }
        return NOT_SQL_TEXT.stream().noneMatch(line::startsWith);
    }

    private static void appendText(StringBuilder sql, String line) {
        if (!sql.isEmpty()) {
            sql.append(' ');
        }
        sql.append(line.trim());
    }
}
