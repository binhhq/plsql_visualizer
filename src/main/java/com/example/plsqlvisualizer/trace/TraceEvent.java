package com.example.plsqlvisualizer.trace;

import com.example.plsqlvisualizer.model.Op;

/**
 * One statement execution the trace file recorded.
 *
 * @param sqlId the trace's {@code sqlid} — the join key back to
 *        {@code ALL_STATEMENTS.SQL_ID}, and the only reliable one. A dynamic
 *        statement has an id here but none in the dictionary, which is exactly
 *        how a trace-only write is recognised.
 * @param depth recursion depth. 0 is the client's own call, 1 is SQL issued from
 *        PL/SQL, and deeper is nested — a trigger's own writes land at 2. It is
 *        <em>not</em> a noise filter: Oracle's dictionary lookups run at 1 too.
 * @param tim the trace's microsecond clock, stamped when the call
 *        <em>finished</em>. So a nested statement's tim precedes its parent's,
 *        and raw tim order would list a trigger's write before the write that
 *        fired it. {@link TraceOrder} undoes that.
 * @param rows rows the execution touched
 * @param sql the statement text as the trace recorded it
 * @param op the DML operation, or null for anything that does not write
 * @param target the table the statement writes, when it can be read off the
 *        text — the point of the whole trace lane for dynamic SQL
 */
public record TraceEvent(String sqlId, int depth, long tim, int rows, String sql,
                         Op op, String target) {

    public boolean isWrite() {
        return op != null;
    }
}
