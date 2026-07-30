package com.example.plsqlvisualizer;

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
import java.nio.file.Path;
import java.util.Map;

/**
 * Extracts the IR from a live schema and writes it to a file.
 *
 * <pre>
 *   --url       JDBC url        (default jdbc:oracle:thin:@//localhost:1521/FREEPDB1)
 *   --user      schema to analyse, and to connect as
 *   --password  password
 *   --entry     UNIT.SUBPROGRAM to walk from; defaults to every uncalled unit
 *   --out       output file     (default target/ir.json)
 *   --check     existing IR to test for staleness instead of extracting
 *   --refresh   existing IR to bring up to date, re-reading only what changed;
 *               rewritten in place unless --out names somewhere else
 * </pre>
 *
 * <p>The renderer consumes the output file and nothing else — it never touches
 * the database (design.md §6).
 */
public final class Cli {

    private static final String DEFAULT_URL = "jdbc:oracle:thin:@//localhost:1521/FREEPDB1";

    private Cli() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parse(args);

        String url = options.getOrDefault("url", System.getenv().getOrDefault("ORACLE_URL", DEFAULT_URL));
        String user = options.getOrDefault("user", System.getenv().getOrDefault("ORACLE_USER", "tscope_test"));
        String password = options.getOrDefault("password",
                System.getenv().getOrDefault("ORACLE_PASSWORD", "tscope"));
        String entry = options.get("entry");
        Path out = Path.of(options.getOrDefault("out", "target/ir.json"));
        String check = options.get("check");
        String refresh = options.get("refresh");

        if (check != null) {
            checkStaleness(url, user, password, Path.of(check));
            return;
        }

        if (refresh != null) {
            Path target = options.containsKey("out") ? out : Path.of(refresh);
            refresh(url, user, password, Path.of(refresh), target, entry);
            return;
        }

        Ir ir;
        try (DictionaryClient client = DictionaryClient.connect(url, user, password)) {
            ir = new IrBuilder(new DictionarySnapshot(client)).build(entry);
        }

        IrJson.write(ir, out);
        printSummary(ir, out);
    }

    private static void checkStaleness(String url, String user, String password, Path irFile)
            throws Exception {
        Ir ir = IrJson.read(irFile);
        StalenessReport report;
        try (DictionaryClient client = DictionaryClient.connect(url, user, password)) {
            report = new StalenessChecker(client).check(ir);
        }

        System.out.printf("%s  (%s)%n", report.summary(), irFile);
        report.changed().forEach(u -> System.out.printf("  changed : %s %s%n", u.type(), u.name()));
        report.added().forEach(u -> System.out.printf("  added   : %s %s%n", u.type(), u.name()));
        report.removed().forEach(u -> System.out.printf("  removed : %s %s%n", u.type(), u.name()));

        if (!report.isFresh()) {
            System.out.printf("Run --refresh %s to re-read just these units and splice them in.%n",
                    irFile);
        }
    }

    private static void refresh(String url, String user, String password, Path irFile, Path out,
                                String entry) throws Exception {
        Ir previous = IrJson.read(irFile);
        IrRefresher.Result result;
        try (DictionaryClient client = DictionaryClient.connect(url, user, password)) {
            result = new IrRefresher(client).refresh(previous, entry);
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

    private static Map<String, String> parse(String[] args) {
        Map<String, String> options = new java.util.LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("Unexpected argument: " + args[i]);
            }
            String key = args[i].substring(2);
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("Missing value for --" + key);
            }
            options.put(key, args[++i]);
        }
        return options;
    }
}
