package com.example.plsqlvisualizer.db;

/**
 * One row of {@code USER_IDENTIFIERS}.
 *
 * <p>{@link #usageContextId()} is a reflexive reference to another row's
 * {@link #usageId()} within the same unit, which is what makes the identifier
 * tree walkable (design.md §4.2).
 */
public record IdentifierRow(
        UnitKey unit,
        long usageId,
        long usageContextId,
        String name,
        String type,
        String usage,
        int line,
        int col,
        String signature) {

    public boolean isSubprogramDefinition() {
        return ("DEFINITION".equals(usage) || "DECLARATION".equals(usage))
                && ("PROCEDURE".equals(type) || "FUNCTION".equals(type));
    }

    /** A FOR-loop index. Its presence as a statement's context proves a loop body. */
    public boolean isIterator() {
        return "ITERATOR".equals(type);
    }

    public boolean isTriggerDefinition() {
        return "TRIGGER".equals(type) && "DEFINITION".equals(usage);
    }
}
