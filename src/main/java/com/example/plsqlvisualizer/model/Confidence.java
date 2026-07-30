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
    TRIGGER_INDUCED("trigger-induced"),
    /**
     * A write only a trace could name: the runtime showed the table a
     * {@code dynamic-unknown} statement actually hit (§5).
     *
     * <p>These edges never replace the {@code dynamic-unknown} edge they explain —
     * static genuinely cannot know that target, and one trace proves what happened
     * once, not what happens. Both edges stay, and {@code resolves} links them.
     */
    TRACE_RESOLVED("trace-resolved");

    private final String value;

    Confidence(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
