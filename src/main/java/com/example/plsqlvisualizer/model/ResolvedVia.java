package com.example.plsqlvisualizer.model;

import com.fasterxml.jackson.annotation.JsonValue;

/** How a write edge's target was reached (write edges only). See design.md §4.3. */
public enum ResolvedVia {
    /** The DML named the table itself. */
    DIRECT("direct"),
    /** The DML named a synonym; resolved to its base object via ALL_SYNONYMS. */
    SYNONYM("synonym"),
    /**
     * The DML named a view. The view is kept as the target — writing through a
     * view is a separate hop we flag rather than chase.
     */
    VIEW("view");

    private final String value;

    ResolvedVia(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
