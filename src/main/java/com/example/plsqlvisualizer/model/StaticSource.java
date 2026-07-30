package com.example.plsqlvisualizer.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/** Provenance of the static (PL/Scope) lane. Versioned independently of the trace. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StaticSource(
        @JsonProperty("generated_at") Instant generatedAt,
        @JsonProperty("units") List<Unit> units) {
}
