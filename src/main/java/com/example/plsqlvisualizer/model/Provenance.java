package com.example.plsqlvisualizer.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Which lane produced the edge. An edge can carry both once a runtime trace
 * confirms a statically-found statement (design.md §5).
 */
public enum Provenance {
    STATIC("static"),
    TRACE("trace");

    private final String value;

    Provenance(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
