package com.example.plsqlvisualizer.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * Provenance of the optional runtime-trace lane. A code change never
 * regenerates a trace — it stays stale until the scenario is re-run, which is
 * why this carries its own timestamp (design.md §5, §7).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TraceSource(
        @JsonProperty("present") boolean present,
        @JsonProperty("captured_at") Instant capturedAt,
        @JsonProperty("scenario") String scenario,
        /** Statements in the IR the traced run never executed — a finding, not a gap. */
        @JsonProperty("not_executed") Integer notExecuted,
        /**
         * Writes the trace saw that could not be tied to any edge. Reported rather
         * than drawn: inventing a source node would be a guess about topology, and
         * dropping the count silently would hide a write we know happened.
         */
        @JsonProperty("unattributed") Integer unattributed) {

    public static TraceSource absent() {
        return new TraceSource(false, null, null, null, null);
    }
}
