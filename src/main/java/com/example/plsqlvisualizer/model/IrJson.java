package com.example.plsqlvisualizer.model;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one mapper both sides of the contract use. Timestamps serialize as ISO-8601
 * instants and nulls are dropped (via {@code @JsonInclude} on the records), so the
 * emitted file matches the sample in design.md §6 shape-for-shape.
 *
 * <p>Jackson 3 handles {@code java.time} and records natively, so no modules are
 * registered here. We only flip the unknown-property default: an unrecognised
 * field means the contract drifted, and that should fail loudly rather than be
 * silently dropped.
 */
public final class IrJson {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private IrJson() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String toJson(Ir ir) {
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(ir);
    }

    public static void write(Ir ir, Path target) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream out = Files.newOutputStream(target)) {
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(out, ir);
        }
    }

    public static Ir read(Path source) throws IOException {
        try (var in = Files.newInputStream(source)) {
            return MAPPER.readValue(in, Ir.class);
        }
    }

    public static Ir parse(String json) {
        return MAPPER.readValue(json, Ir.class);
    }
}
