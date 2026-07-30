package com.example.plsqlvisualizer.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Builder;

/**
 * A directed edge: either a call (unit → unit) or a write (unit → table). All
 * the metadata the renderer needs to draw and explain the edge lives here —
 * per design.md §6 the renderer never touches the DB.
 *
 * <p>Optional components stay null and are omitted from the JSON; {@code line}
 * and {@code sqlId} are the join keys back to the database for "jump to source".
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Edge(
        @JsonProperty("id") String id,
        @JsonProperty("type") EdgeType type,
        /** Write edges only. */
        @JsonProperty("op") Op op,
        @JsonProperty("from") String from,
        @JsonProperty("to") String to,
        /** Static ordinal (§4.6) — distinct from {@link #traceOrder}. */
        @JsonProperty("step") Integer step,
        @JsonProperty("line") Integer line,
        @JsonProperty("sql_id") String sqlId,
        @JsonProperty("confidence") Confidence confidence,
        /** Write edges only. */
        @JsonProperty("resolved_via") ResolvedVia resolvedVia,
        @JsonProperty("reachability") Reachability reachability,
        /** The branch condition, when reachability is branch-conditional. */
        @JsonProperty("guard") String guard,
        /** Raw statement text for a dynamic-unknown write, when available. */
        @JsonProperty("raw_text") String rawText,
        /** Set on trigger-induced edges. */
        @JsonProperty("via_trigger") String viaTrigger,
        @JsonProperty("provenance") List<Provenance> provenance,
        /** True order from a runtime trace; null until the trace lane runs. */
        @JsonProperty("trace_order") Integer traceOrder) {
}
