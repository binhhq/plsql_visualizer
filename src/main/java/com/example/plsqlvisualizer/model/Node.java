package com.example.plsqlvisualizer.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A vertex in the data-flow graph: a program unit, a table, or the unknown
 * sentinel. Ids are stable strings so the renderer can join edges without a DB.
 *
 * <p>Id conventions:
 * <ul>
 *   <li>{@code PROC:OWNER.UNIT.SUBPROGRAM}</li>
 *   <li>{@code TBL:OWNER.NAME}</li>
 *   <li>{@code TBL:__UNKNOWN__} — the dynamic-SQL sentinel</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Node(
        @JsonProperty("id") String id,
        @JsonProperty("kind") NodeKind kind,
        @JsonProperty("unit") String unit,
        @JsonProperty("subprogram") String subprogram,
        @JsonProperty("unit_type") String unitType,
        @JsonProperty("schema") String schema,
        @JsonProperty("name") String name) {

    /** Id of the single sentinel node every dynamic-SQL write points at. */
    public static final String UNKNOWN_ID = "TBL:__UNKNOWN__";

    public static String procId(String owner, String unit, String subprogram) {
        return "PROC:" + owner + "." + unit + "." + subprogram;
    }

    public static String tableId(String owner, String name) {
        return "TBL:" + owner + "." + name;
    }

    public static Node programUnit(String owner, String unit, String subprogram, String unitType) {
        return new Node(procId(owner, unit, subprogram), NodeKind.PROGRAM_UNIT,
                unit, subprogram, unitType, null, null);
    }

    public static Node table(String owner, String name) {
        return new Node(tableId(owner, name), NodeKind.TABLE, null, null, null, owner, name);
    }

    public static Node unknown() {
        return new Node(UNKNOWN_ID, NodeKind.UNKNOWN, null, null, null, null, null);
    }
}
