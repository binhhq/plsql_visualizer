package com.example.plsqlvisualizer.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * How much the extractor actually knows about an edge. This is the honesty
 * signal of design.md §2.3 — anything static could not resolve says so here
 * rather than being dropped.
 */
public enum Confidence {
    /** Static analysis resolved the target to a real object. */
    RESOLVED("resolved"),
    /** EXECUTE IMMEDIATE / DBMS_SQL, or a target that resolved to nothing (§4.4). */
    DYNAMIC_UNKNOWN("dynamic-unknown"),
    /** Found by the trigger pass, not present in the procedure's own statements (§4.5). */
    TRIGGER_INDUCED("trigger-induced");

    private final String value;

    Confidence(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
