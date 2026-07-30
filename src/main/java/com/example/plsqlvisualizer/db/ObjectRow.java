package com.example.plsqlvisualizer.db;

import java.time.Instant;

/**
 * One row of {@code USER_OBJECTS}. The {@code LAST_DDL_TIME} is what the
 * staleness check compares against the IR to re-extract only changed units
 * (design.md §7).
 */
public record ObjectRow(UnitKey unit, Instant lastDdlTime) {
}
