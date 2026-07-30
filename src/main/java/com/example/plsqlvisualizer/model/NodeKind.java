package com.example.plsqlvisualizer.model;

import com.fasterxml.jackson.annotation.JsonValue;

/** What a node represents. See design.md §6. */
public enum NodeKind {
    PROGRAM_UNIT("program_unit"),
    TABLE("table"),
    /** The {@code TBL:__UNKNOWN__} sentinel for dynamic-SQL targets (§4.4). */
    UNKNOWN("unknown");

    private final String value;

    NodeKind(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
