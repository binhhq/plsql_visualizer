package com.example.plsqlvisualizer.db;

/**
 * Identifies a library unit. PL/Scope keys everything by
 * {@code (OBJECT_NAME, OBJECT_TYPE)}, and usage ids are only unique within one
 * such unit — so this pair is the key for every in-memory lookup.
 */
public record UnitKey(String name, String type) {

    public static UnitKey of(String name, String type) {
        return new UnitKey(name, type);
    }

    public boolean isTrigger() {
        return "TRIGGER".equals(type);
    }
}
