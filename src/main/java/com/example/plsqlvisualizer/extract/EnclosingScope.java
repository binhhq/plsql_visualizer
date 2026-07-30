package com.example.plsqlvisualizer.extract;

/**
 * Where a statement or call actually sits, recovered by walking the PL/Scope
 * usage-context tree upwards.
 *
 * @param subprogram the enclosing PROCEDURE/FUNCTION (or the trigger's own name)
 * @param insideIterator whether a FOR-loop index was crossed on the way up —
 *        Oracle's own, authoritative signal that the statement is in a loop body
 */
public record EnclosingScope(String subprogram, boolean insideIterator) {

    /** Used when the walk finds no subprogram, e.g. a statement in a package initialiser. */
    public static EnclosingScope unknown() {
        return new EnclosingScope("__ANONYMOUS__", false);
    }
}
