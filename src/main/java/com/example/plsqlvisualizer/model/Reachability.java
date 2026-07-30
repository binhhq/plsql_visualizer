package com.example.plsqlvisualizer.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Whether the statement always runs, or sits under a branch / loop. Static
 * order is a superset — a conditional edge may never execute in a given run,
 * which is exactly the gap the trace overlay makes visible.
 */
public enum Reachability {
    UNCONDITIONAL("unconditional"),
    BRANCH_CONDITIONAL("branch-conditional"),
    LOOP("loop");

    private final String value;

    Reachability(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
