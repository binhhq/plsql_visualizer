package com.example.plsqlvisualizer.web;

import com.example.plsqlvisualizer.ExtractionService;
import com.example.plsqlvisualizer.config.VisualizerProperties;
import com.example.plsqlvisualizer.db.DictionaryClient;
import com.example.plsqlvisualizer.db.UnitKey;
import com.example.plsqlvisualizer.model.Ir;
import com.example.plsqlvisualizer.model.IrJson;
import com.example.plsqlvisualizer.model.Meta;
import com.example.plsqlvisualizer.model.StaticSource;
import com.example.plsqlvisualizer.model.TraceSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The serve profile's HTTP face. Only active under {@code --spring.profiles.active=serve};
 * the default profile is still a run-once tool that extracts and exits.
 *
 * <p>Nothing is read from the database until a unit has been named. A page load
 * that extracted the whole schema was workable against a handful of fixture
 * packages and simply never returns against a real one — so {@code GET /} now
 * serves an empty graph and a search box, and the extraction happens for the one
 * unit that gets picked. Whole-schema extraction is still available, but only by
 * asking for it explicitly.
 */
@RestController
@Profile("serve")
public class IrController {

    private static final Logger LOG = LoggerFactory.getLogger(IrController.class);

    /** The renderer's embedded-IR block, which this replaces on the way out. */
    private static final Pattern IR_BLOCK = Pattern.compile(
            "(<script[^>]*id=\"ir-data\"[^>]*>)(.*?)(</script>)", Pattern.DOTALL);

    /** Scoped graphs to keep. Small: each holds a whole IR, and they are cheap to rebuild. */
    private static final int CACHE_SIZE = 16;

    private final ExtractionService extraction;
    private final VisualizerProperties props;

    /** The whole-schema IR, built only when something explicitly asks for one. */
    private volatile Ir cached;
    private volatile Instant cachedAt;

    /** Recently built scoped graphs, keyed by {@code UNIT@depth}. */
    private final Map<String, Ir> scoped = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Ir> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    /** Cached because the empty page wants the schema name without an extraction. */
    private volatile String schemaName;

    public IrController(ExtractionService extraction, VisualizerProperties props) {
        this.extraction = extraction;
        this.props = props;
    }

    /**
     * The renderer. With {@code ?unit=} it carries that unit's graph; without, an
     * empty one — the page loads instantly and the search box does the rest.
     */
    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> page(@RequestParam(required = false) String unit,
                                       @RequestParam(required = false) Integer depth)
            throws Exception {
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
                + Matcher.quoteReplacement("\n" + IrJson.toJson(pageIr(unit, depth)) + "\n")
                + m.group(3);
        return ResponseEntity.ok(html.substring(0, m.start()) + injected + html.substring(m.end()));
    }

    private Ir pageIr(String unit, Integer depth) throws Exception {
        if (unit != null && !unit.isBlank()) {
            return scopedIr(unit, depth);
        }
        // Configured units still auto-load: someone who pinned plsql.units has
        // already named what they want and should not have to name it again.
        return props.units().isEmpty() ? emptyIr() : ir();
    }

    /**
     * Units whose name contains {@code q}. Hits USER_OBJECTS only, so it stays
     * responsive on a schema where reading any one unit is not.
     */
    @GetMapping(value = "/api/units", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, String>> units(@RequestParam String q,
                                           @RequestParam(defaultValue = "20") int limit) throws Exception {
        if (q.isBlank()) {
            return List.of();
        }
        List<UnitKey> found;
        try {
            found = extraction.searchUnits(q, Math.min(limit, 100));
        } catch (SQLException e) {
            // An unreachable database is not a server fault, and the search box
            // showing "ORA-12541: no listener" beats it showing "500".
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), e);
        }
        List<Map<String, String>> out = new ArrayList<>();
        for (UnitKey key : found) {
            out.add(Map.of("name", key.name(), "type", key.type()));
        }
        return out;
    }

    /**
     * @param unit the unit to build a graph around; omitted falls back to whatever
     *        {@code plsql.units} configured, and fails when that is empty too
     *        rather than starting a whole-schema read nobody asked for
     */
    @GetMapping(value = "/api/ir", produces = MediaType.APPLICATION_JSON_VALUE)
    public String irJson(@RequestParam(required = false) String unit,
                         @RequestParam(required = false) Integer depth) throws Exception {
        if (unit == null || unit.isBlank()) {
            if (props.units().isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "name a unit: /api/ir?unit=PKG_ORDER. Extracting the whole schema is"
                                + " POST /api/extract, and on a large one it takes minutes.");
            }
            return IrJson.toJson(ir());
        }
        return IrJson.toJson(scopedIr(unit, depth));
    }

    /** Re-reads the whole schema. Explicit, because on a large one it is slow. */
    @PostMapping(value = "/api/extract", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> reextract() throws Exception {
        synchronized (this) {
            cached = null;
            scoped.clear();
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
                "scopedGraphs", scoped.size(),
                "extractedAt", String.valueOf(cachedAt));
    }

    private Ir scopedIr(String unit, Integer depth) throws Exception {
        String key = unit.trim().toUpperCase(java.util.Locale.ROOT)
                + "@" + (depth == null ? props.depth() : depth);
        synchronized (scoped) {
            Ir hit = scoped.get(key);
            if (hit != null) {
                return hit;
            }
        }

        ExtractionService.Scoped result;
        try {
            result = extraction.extractScoped(unit, depth);
        } catch (IllegalArgumentException e) {
            // A name that matches nothing is the user mistyping, not a server fault.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        }
        LOG.info("Scoped graph {}: {} node(s), {} edge(s) from {} unit(s)", key,
                result.ir().nodes().size(), result.ir().edges().size(),
                result.scope().units().size());

        synchronized (scoped) {
            scoped.put(key, result.ir());
        }
        return result.ir();
    }

    /** A graph with nothing in it, so the page can render before anything is chosen. */
    private Ir emptyIr() throws Exception {
        return new Ir(
                new Meta(Meta.SCHEMA_VERSION, schema(), null,
                        new StaticSource(Instant.now(), List.of()), TraceSource.absent()),
                List.of(), List.of());
    }

    /**
     * The schema we are connected as, without extracting anything. Best effort:
     * an unreachable database must still render a page that can say so, rather
     * than a stack trace.
     */
    private String schema() {
        String known = schemaName;
        if (known != null) {
            return known;
        }
        try (DictionaryClient client = extraction.connect()) {
            schemaName = client.schema();
            return schemaName;
        } catch (Exception e) {
            LOG.warn("Could not reach {}: {}", props.oracle().url(), e.getMessage());
            return "(not connected)";
        }
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
