package com.example.plsqlvisualizer.db;

/** One row of {@code ALL_SYNONYMS} — maps a synonym to the object it stands for. */
public record SynonymRow(String synonymName, String tableOwner, String tableName) {
}
