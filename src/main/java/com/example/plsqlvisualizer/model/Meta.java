package com.example.plsqlvisualizer.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * IR header. The two sources are versioned separately so the renderer can show
 * {@code static: fresh · trace: stale} and nobody mistakes an old execution
 * order for the current code (design.md §7).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Meta(
        @JsonProperty("schema_version") String schemaVersion,
        @JsonProperty("db") String db,
        /** Optional root of the walk, e.g. {@code APP.PKG_ORDER.SUBMIT}. */
        @JsonProperty("entry_point") String entryPoint,
        @JsonProperty("static_source") StaticSource staticSource,
        @JsonProperty("trace_source") TraceSource traceSource) {

    /** The frozen contract version this build emits. */
    public static final String SCHEMA_VERSION = "1.0";
}
