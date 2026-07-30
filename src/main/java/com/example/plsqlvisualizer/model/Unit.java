package com.example.plsqlvisualizer.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * A library unit that contributed to this IR, stamped with the
 * {@code LAST_DDL_TIME} it had at extraction time. The staleness check
 * (design.md §7) compares this against the dictionary to re-extract only
 * what changed.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Unit(
        @JsonProperty("owner") String owner,
        @JsonProperty("name") String name,
        @JsonProperty("type") String type,
        @JsonProperty("last_ddl_time") Instant lastDdlTime) {
}
