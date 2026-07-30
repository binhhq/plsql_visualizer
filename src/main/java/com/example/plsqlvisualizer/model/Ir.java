package com.example.plsqlvisualizer.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * The whole contract between extractor and renderer (design.md §6).
 *
 * <p>Design rule: the renderer must be able to draw the entire graph from this
 * object alone, with no database access.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Ir(
        @JsonProperty("meta") Meta meta,
        @JsonProperty("nodes") List<Node> nodes,
        @JsonProperty("edges") List<Edge> edges) {
}
