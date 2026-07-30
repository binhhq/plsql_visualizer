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

    /**
     * The contract version this build emits.
     *
     * <p>1.1 added the trace lane's additive fields — {@code trace_count},
     * {@code resolves}, the {@code trace-resolved} confidence, and the two
     * {@code trace_source} counters. Nothing was removed or re-typed, so a 1.0
     * renderer still draws a 1.1 file; it just ignores what it does not know.
     */
    public static final String SCHEMA_VERSION = "1.1";
}
