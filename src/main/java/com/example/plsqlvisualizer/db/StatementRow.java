package com.example.plsqlvisualizer.db;

/**
 * One row of {@code USER_STATEMENTS} — a SQL statement PL/Scope recorded at
 * compile time, with the {@code SQL_ID} that joins it back to the shared pool
 * (and to a runtime trace, in phase 2).
 */
public record StatementRow(
        UnitKey unit,
        long usageId,
        long usageContextId,
        String type,
        int line,
        int col,
        String sqlId) {

    /** Dynamic execution: the target is a runtime string, invisible to static analysis. */
    public boolean isDynamic() {
        return type != null && type.startsWith("EXECUTE");
    }
}
