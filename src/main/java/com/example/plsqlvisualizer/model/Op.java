package com.example.plsqlvisualizer.model;

/**
 * The DML operation behind a write edge. Mirrors {@code ALL_STATEMENTS.TYPE}.
 * MERGE stays MERGE — it is never split into INSERT + UPDATE (test-fixtures.md #8).
 */
public enum Op {
    INSERT,
    UPDATE,
    DELETE,
    MERGE;

    /** Null-safe lookup for a dictionary {@code TYPE} value; returns null if not a DML op. */
    public static Op fromStatementType(String type) {
        if (type == null) {
            return null;
        }
        for (Op op : values()) {
            if (op.name().equalsIgnoreCase(type.trim())) {
                return op;
            }
        }
        return null;
    }
}
