package com.example.plsqlvisualizer;

import com.example.plsqlvisualizer.config.VisualizerProperties;
import com.example.plsqlvisualizer.db.DictionaryClient;
import com.example.plsqlvisualizer.extract.DictionarySnapshot;
import com.example.plsqlvisualizer.freshness.IrRefresher;
import com.example.plsqlvisualizer.freshness.StalenessChecker;
import com.example.plsqlvisualizer.freshness.StalenessReport;
import com.example.plsqlvisualizer.graph.IrBuilder;
import com.example.plsqlvisualizer.model.Confidence;
import com.example.plsqlvisualizer.model.Edge;
import com.example.plsqlvisualizer.model.Ir;
import com.example.plsqlvisualizer.model.IrJson;
import com.example.plsqlvisualizer.model.Provenance;
import com.example.plsqlvisualizer.model.TraceSource;
import com.example.plsqlvisualizer.trace.TraceEvent;
import com.example.plsqlvisualizer.trace.TraceMerger;
import com.example.plsqlvisualizer.trace.TraceParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Runs one extraction task at startup and lets the application exit.
 *
 * <p>Configured entirely through {@link VisualizerProperties} — see
 * {@code application.yaml} for the settings and their defaults, and override per
 * run with {@code --plsql.task=…} style arguments.
 *
 * <p>Progress goes to {@code System.out} rather than the logger on purpose: this
 * output is the deliverable report a person reads, not diagnostics.
 *
 * <p>The renderer consumes the output file and nothing else — it never touches
 * the database (design.md §6).
 */
@Component
@Profile("!test")
public class IrRunner implements ApplicationRunner {

    private final VisualizerProperties props;

    public IrRunner(VisualizerProperties props) {
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        switch (props.task()) {
            case CHECK -> checkStaleness(required("ir", props.ir()));
            case REFRESH -> {
                Path in = required("ir", props.ir());
                refresh(in, props.out() == null ? in : props.out());
            }
            case TRACE -> {
                Path in = required("ir", props.ir());
                Path trace = required("trace-file", props.traceFile());
                // scenario defaults to the trace filename, as the old --trace did.
                overlayTrace(in, trace, props.out() == null ? in : props.out(),
                        props.scenario() == null
                                ? trace.getFileName().toString()
                                : props.scenario());
            }
            case EXTRACT -> extract(props.out() == null ? Path.of("target/ir.json") : props.out());
        }
    }

    /**
     * Fails before opening a connection when a task's required input is missing,
     * naming the property so the message points at the fix.
     */
    private Path required(String name, Path value) {
        if (value == null) {
            throw new IllegalArgumentException("plsql.task="
                    + props.task().name().toLowerCase(java.util.Locale.ROOT)
                    + " needs plsql." + name + " to be set");
        }
        return value;
    }

    private void extract(Path out) throws Exception {
        Ir ir;
        try (DictionaryClient client = connect()) {
            ir = new IrBuilder(new DictionarySnapshot(client)).build(props.entry());
        }

        IrJson.write(ir, out);
        printSummary(ir, out);
    }

    private DictionaryClient connect() throws Exception {
        VisualizerProperties.Oracle db = props.oracle();
        return DictionaryClient.connect(db.url(), db.username(), db.password());
    }

    private void checkStaleness(Path irFile) throws Exception {
        Ir ir = IrJson.read(irFile);
        StalenessReport report;
        try (DictionaryClient client = connect()) {
            report = new StalenessChecker(client).check(ir);
        }

        System.out.printf("%s  (%s)%n", report.summary(), irFile);
        report.changed().forEach(u -> System.out.printf("  changed : %s %s%n", u.type(), u.name()));
        report.added().forEach(u -> System.out.printf("  added   : %s %s%n", u.type(), u.name()));
        report.removed().forEach(u -> System.out.printf("  removed : %s %s%n", u.type(), u.name()));

        if (!report.isFresh()) {
            System.out.printf(
                    "Run with --plsql.task=refresh --plsql.ir=%s to re-read just these units"
                            + " and splice them in.%n",
                    irFile);
        }
    }

    private void refresh(Path irFile, Path out) throws Exception {
        Ir previous = IrJson.read(irFile);
        IrRefresher.Result result;
        try (DictionaryClient client = connect()) {
            result = new IrRefresher(client).refresh(previous, props.entry());
        }

        System.out.printf("%s  (%s)%n", result.report().summary(), irFile);
        if (!result.changedAnything()) {
            System.out.println("Nothing to do — the IR already matches the schema.");
            return;
        }

        if (result.fullRebuild()) {
            System.out.printf("Fell back to a full extraction: %s.%n", result.fallbackReason());
        } else {
            result.reextracted().forEach(u -> System.out.printf("  re-read : %s %s%n",
                    u.type(), u.name()));
            result.pruned().forEach(u -> System.out.printf("  pruned  : %s %s%n",
                    u.type(), u.name()));
        }

        IrJson.write(result.ir(), out);
        System.out.printf("  edges   : %d → %d%n",
                previous.edges() == null ? 0 : previous.edges().size(),
                result.ir().edges().size());
        printSummary(result.ir(), out);
    }

    /** Overlays a 10046 trace on an existing IR. No database: the IR is the static truth. */
    private void overlayTrace(Path irFile, Path traceFile, Path out, String scenario)
            throws Exception {
        Ir ir = IrJson.read(irFile);
        List<TraceEvent> events = new TraceParser().parse(traceFile);
        Instant capturedAt = Files.getLastModifiedTime(traceFile).toInstant();

        Ir merged = new TraceMerger(scenario, capturedAt).merge(ir, events);

        long confirmed = merged.edges().stream()
                .filter(e -> e.provenance() != null && e.provenance().contains(Provenance.TRACE))
                .count();
        long resolved = merged.edges().stream()
                .filter(e -> e.confidence() == Confidence.TRACE_RESOLVED)
                .count();
        TraceSource source = merged.meta().traceSource();

        System.out.printf("Trace %s (%d execution events)%n", traceFile, events.size());
        System.out.printf("  scenario       : %s%n", scenario);
        System.out.printf("  confirmed      : %d edges carry provenance trace%n", confirmed);
        System.out.printf("  trace-resolved : %d write(s) only the trace could name%n", resolved);
        if (source.notExecuted() != null) {
            System.out.printf("  NOT executed   : %d statement(s) the run never reached%n",
                    source.notExecuted());
        }
        if (source.unattributed() != null) {
            System.out.printf("  unattributed   : %d write(s) seen but tied to no edge%n",
                    source.unattributed());
        }

        IrJson.write(merged, out);
        System.out.printf("Wrote %s%n", out.toAbsolutePath());
    }

    private static void printSummary(Ir ir, Path out) {
        long writes = ir.edges().stream().filter(e -> e.op() != null).count();
        long calls = ir.edges().size() - writes;
        long unknown = count(ir, Confidence.DYNAMIC_UNKNOWN);
        long triggered = count(ir, Confidence.TRIGGER_INDUCED);

        System.out.printf("Wrote %s%n", out.toAbsolutePath());
        System.out.printf("  schema      : %s%n", ir.meta().db());
        System.out.printf("  entry point : %s%n",
                ir.meta().entryPoint() == null ? "(all uncalled units)" : ir.meta().entryPoint());
        System.out.printf("  nodes       : %d%n", ir.nodes().size());
        System.out.printf("  edges       : %d  (%d write, %d call)%n", ir.edges().size(), writes, calls);
        System.out.printf("  flagged     : %d dynamic-unknown, %d trigger-induced%n", unknown, triggered);

        if (unknown > 0) {
            System.out.println("  note: dynamic-unknown edges are writes whose target could not be");
            System.out.println("        determined statically. They are shown, not dropped.");
        }
    }

    private static long count(Ir ir, Confidence confidence) {
        return ir.edges().stream().map(Edge::confidence).filter(confidence::equals).count();
    }
}
