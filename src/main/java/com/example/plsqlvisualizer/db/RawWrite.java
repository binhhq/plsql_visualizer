package com.example.plsqlvisualizer.db;

/**
 * Result of extraction query A: a DML statement joined to the object it writes.
 * The target is still unresolved here — it may be a TABLE, VIEW or SYNONYM.
 */
public record RawWrite(
        UnitKey unit,
        long statementUsageId,
        long statementContextId,
        String op,
        int line,
        String sqlId,
        String targetObject,
        String targetKind) {
}
