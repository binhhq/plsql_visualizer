package com.example.plsqlvisualizer.web;

import com.example.plsqlvisualizer.ExtractionService;
import com.example.plsqlvisualizer.config.VisualizerProperties;
import com.example.plsqlvisualizer.model.Ir;
import com.example.plsqlvisualizer.model.IrJson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/**
 * The serve profile's HTTP face. Only active under {@code --spring.profiles.active=serve};
 * the default profile is still a run-once tool that extracts and exits.
 *
 * <p>The IR is extracted once and held, because an extraction against a large
 * schema costs minutes — a page load must not pay that, and two viewers must not
 * pay it twice. {@code POST /api/extract} is the way to ask for a fresh one.
 */
@RestController
@Profile("serve")
public class IrController {

    private static final Logger LOG = LoggerFactory.getLogger(IrController.class);

    /** The renderer's embedded-IR block, which this replaces on the way out. */
    private static final Pattern IR_BLOCK = Pattern.compile(
            "(<script[^>]*id=\"ir-data\"[^>]*>)(.*?)(</script>)", Pattern.DOTALL);

    private final ExtractionService extraction;
    private final VisualizerProperties props;

    private volatile Ir cached;
    private volatile Instant cachedAt;

    public IrController(ExtractionService extraction, VisualizerProperties props) {
        this.extraction = extraction;
        this.props = props;
    }

    /** The renderer, serving whatever IR is currently held. */
    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> page() throws Exception {
        Path renderer = props.renderer();
        if (renderer == null || !Files.isReadable(renderer)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "plsql.renderer does not point at a readable file: " + renderer);
        }

        String html = Files.readString(renderer);
        Matcher m = IR_BLOCK.matcher(html);
        if (!m.find()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "no <script id=\"ir-data\"> block in " + renderer);
        }
        // The IR is injected rather than shipped in the file, so the page always
        // shows this schema and not whatever fixture the file was saved with.
        String injected = m.group(1)
                + Matcher.quoteReplacement("\n" + IrJson.toJson(ir()) + "\n")
                + m.group(3);
        return ResponseEntity.ok(html.substring(0, m.start()) + injected + html.substring(m.end()));
    }

    @GetMapping(value = "/api/ir", produces = MediaType.APPLICATION_JSON_VALUE)
    public String irJson() throws Exception {
        return IrJson.toJson(ir());
    }

    /** Re-reads the schema. The only thing that invalidates the held IR. */
    @PostMapping(value = "/api/extract", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> reextract() throws Exception {
        synchronized (this) {
            cached = null;
        }
        Ir ir = ir();
        return Map.of(
                "schema", ir.meta().db(),
                "nodes", ir.nodes().size(),
                "edges", ir.edges().size(),
                "extractedAt", String.valueOf(cachedAt));
    }

    @GetMapping(value = "/api/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> health() {
        return Map.of(
                "status", "up",
                "irLoaded", cached != null,
                "extractedAt", String.valueOf(cachedAt));
    }

    private Ir ir() throws Exception {
        Ir local = cached;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (cached == null) {
                LOG.info("Extracting from {}", props.oracle().url());
                cached = extraction.extract();
                cachedAt = Instant.now();
            }
            return cached;
        }
    }
}
