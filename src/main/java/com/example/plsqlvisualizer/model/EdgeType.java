package com.example.plsqlvisualizer.model;

import com.fasterxml.jackson.annotation.JsonValue;

/** {@code edge.type} — a data-flow write, or a procedure call. */
public enum EdgeType {
    WRITE("write"),
    CALL("call");

    private final String value;

    EdgeType(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
