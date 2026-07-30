package com.example.plsqlvisualizer.db;

/**
 * One row of {@code USER_TRIGGERS} — which table fires which trigger, and on
 * what event. The trigger's own writes are pulled separately from the
 * statements of its {@code TRIGGER} unit (design.md §4.5).
 */
public record TriggerRow(String triggerName, String tableName, String triggeringEvent) {
}
