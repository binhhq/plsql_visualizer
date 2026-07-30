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
        @JsonProperty("scenario") String scenario) {

    public static TraceSource absent() {
        return new TraceSource(false, null, null);
    }
}
