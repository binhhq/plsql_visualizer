package com.example.plsqlvisualizer.db;

/**
 * Result of extraction query B: a CALL identifier resolved, via its globally
 * unique {@code SIGNATURE}, to the unit that declares the callee.
 */
public record RawCall(
        UnitKey callerUnit,
        long callUsageId,
        long callContextId,
        int line,
        String calleeUnit,
        String calleeUnitType,
        String calleeSubprogram) {
}
