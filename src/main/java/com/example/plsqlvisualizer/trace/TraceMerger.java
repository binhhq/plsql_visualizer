package com.example.plsqlvisualizer.trace;

import com.example.plsqlvisualizer.model.Confidence;
import com.example.plsqlvisualizer.model.Edge;
import com.example.plsqlvisualizer.model.EdgeType;
import com.example.plsqlvisualizer.model.Ir;
import com.example.plsqlvisualizer.model.Meta;
import com.example.plsqlvisualizer.model.Node;
import com.example.plsqlvisualizer.model.Provenance;
import com.example.plsqlvisualizer.model.TraceSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Overlays a parsed trace onto an IR (design.md §5): what the code <em>can</em>
 * do, annotated with what it <em>did</em> once.
 *
 * <p>The static lane stays authoritative. A trace is one execution, so it never
 * deletes an edge, never rewrites a target, and never downgrades a confidence —
 * it only adds: {@code trace_order}, {@code trace_count}, {@code trace} in
 * {@code provenance}, and new {@code trace-resolved} edges for writes static
 * could not name.
 *
 * <p>Three findings fall out of the overlay, and all three are reported rather
 * than smoothed over:
 * <ul>
 *   <li>an edge with a {@code sql_id} the trace never executed — the branch that
 *       did not run;</li>
 *   <li>a write the trace saw that no {@code sql_id} matches — dynamic SQL, whose
 *       real target only the trace knows;</li>
 *   <li>such a write that cannot be tied to any edge — counted in
 *       {@code trace_source.unattributed}, because inventing a source node would be
 *       a guess about topology and dropping it would hide a write we know happened.</li>
 * </ul>
 *
 * <p>Call edges get no {@code trace_order}. A 10046 trace records SQL, not PL/SQL
 * control flow; the real call tree needs {@code DBMS_HPROF}, which is a separate
 * source. Leaving calls unstamped is honest — claiming a call ran because the SQL
 * inside it did would be an inference, not a measurement.
 */
public class TraceMerger {

    /**
     * Oracle names its own dictionary objects with a trailing {@code $}
     * ({@code sysauth$}, {@code idl_ub1$}), and a trace is full of recursive DML
     * against them. Depth cannot separate that noise from real writes — a trigger's
     * write is depth 2, and Oracle's lookups run at depth 1 — but the naming
     * convention can.
     */
    private static boolean isDictionaryObject(String target) {
        return target == null || target.contains("$");
    }

    private final String scenario;
    private final Instant capturedAt;

    public TraceMerger(String scenario, Instant capturedAt) {
        this.scenario = scenario;
        this.capturedAt = capturedAt;
    }

    public Ir merge(Ir ir, List<TraceEvent> asRecorded) {
        List<TraceEvent> ordered = TraceOrder.readerOrder(asRecorded);
        Set<String> irSqlIds = sqlIdsOf(ir);

        // Dense 1..N over the executions that mean something to this IR, so the
        // renderer can index trace_order the way it indexes step.
        Map<String, List<Integer>> positionsBySqlId = new LinkedHashMap<>();
        List<TraceEvent> unmatchedWrites = new ArrayList<>();
        int position = 0;

        for (TraceEvent event : ordered) {
            if (irSqlIds.contains(event.sqlId())) {
                positionsBySqlId.computeIfAbsent(event.sqlId(), k -> new ArrayList<>())
                        .add(++position);
            } else if (event.isWrite() && !isDictionaryObject(event.target())) {
                unmatchedWrites.add(event);
                ++position;
            }
        }

        List<Edge> edges = new ArrayList<>();
        int notExecuted = 0;
        for (Edge edge : ir.edges()) {
            List<Integer> ran = positionsBySqlId.get(edge.sqlId());
            if (edge.sqlId() == null) {
                edges.add(edge);
            } else if (ran == null) {
                notExecuted++;
                edges.add(edge);
            } else {
                edges.add(stamped(edge, ran));
            }
        }

        Resolutions resolutions = resolve(ir, unmatchedWrites, ordered, positionsBySqlId);
        edges.addAll(resolutions.edges());

        List<Node> nodes = new ArrayList<>(ir.nodes());
        resolutions.nodes().stream()
                .filter(node -> ir.nodes().stream().noneMatch(n -> n.id().equals(node.id())))
                .forEach(nodes::add);

        return new Ir(withTraceSource(ir.meta(), notExecuted, resolutions.unattributed()),
                nodes, edges);
    }

    private Edge stamped(Edge edge, List<Integer> ran) {
        List<Provenance> provenance = new ArrayList<>(
                edge.provenance() == null ? List.of() : edge.provenance());
        if (!provenance.contains(Provenance.TRACE)) {
            provenance.add(Provenance.TRACE);
        }
        return rebuild(edge).provenance(provenance)
                .traceOrder(ran.get(0))
                .traceCount(ran.size())
                .build();
    }

    /** What the unmatched writes turned into. */
    private record Resolutions(List<Edge> edges, List<Node> nodes, int unattributed) {
    }

    /**
     * Ties each write the trace saw but the dictionary does not know to the
     * {@code dynamic-unknown} edge it explains.
     *
     * <p>Matched by neighbourhood, because nothing else can be: a 10046 trace does
     * not say which subprogram issued a statement. The write ran between two
     * statements we <em>did</em> match, so the edge that explains it must be a
     * {@code dynamic-unknown} edge sitting between those same two edges in static
     * order — and its op must agree. One candidate means a confident answer;
     * anything else stays unattributed rather than guessed.
     */
    private Resolutions resolve(Ir ir, List<TraceEvent> unmatched, List<TraceEvent> ordered,
                                Map<String, List<Integer>> matched) {
        List<Edge> created = new ArrayList<>();
        List<Node> nodes = new ArrayList<>();
        int unattributed = 0;
        int sequence = 0;

        for (TraceEvent write : unmatched) {
            List<Edge> candidates = candidatesFor(ir, write, ordered, matched);
            if (candidates.size() != 1) {
                unattributed++;
                continue;
            }
            Edge explains = candidates.get(0);
            Node target = Node.table(ir.meta().db(), write.target().toUpperCase(Locale.ROOT));
            nodes.add(target);
            created.add(Edge.builder()
                    .id("t" + (++sequence))
                    .type(EdgeType.WRITE)
                    .op(write.op())
                    .from(explains.from())
                    .to(target.id())
                    .sqlId(write.sqlId())
                    .confidence(Confidence.TRACE_RESOLVED)
                    .reachability(explains.reachability())
                    .provenance(List.of(Provenance.TRACE))
                    .traceOrder(positionOf(write, ordered, matched))
                    .traceCount(1)
                    .resolves(explains.id())
                    .build());
        }
        return new Resolutions(created, nodes, unattributed);
    }

    private List<Edge> candidatesFor(Ir ir, TraceEvent write, List<TraceEvent> ordered,
                                     Map<String, List<Integer>> matched) {
        Integer before = lastMatchedStepBefore(ir, write, ordered, matched);
        Integer after = firstMatchedStepAfter(ir, write, ordered, matched);

        return ir.edges().stream()
                .filter(e -> e.confidence() == Confidence.DYNAMIC_UNKNOWN)
                .filter(e -> e.op() == null || e.op() == write.op())
                .filter(e -> e.step() != null)
                .filter(e -> before == null || e.step() > before)
                .filter(e -> after == null || e.step() < after)
                .toList();
    }

    private Integer lastMatchedStepBefore(Ir ir, TraceEvent write, List<TraceEvent> ordered,
                                          Map<String, List<Integer>> matched) {
        Integer step = null;
        for (TraceEvent event : ordered) {
            if (event == write) {
                return step;
            }
            Integer candidate = stepOf(ir, event, matched);
            if (candidate != null) {
                step = candidate;
            }
        }
        return step;
    }

    private Integer firstMatchedStepAfter(Ir ir, TraceEvent write, List<TraceEvent> ordered,
                                          Map<String, List<Integer>> matched) {
        boolean past = false;
        for (TraceEvent event : ordered) {
            if (event == write) {
                past = true;
                continue;
            }
            if (!past) {
                continue;
            }
            Integer step = stepOf(ir, event, matched);
            if (step != null) {
                return step;
            }
        }
        return null;
    }

    private Integer stepOf(Ir ir, TraceEvent event, Map<String, List<Integer>> matched) {
        if (!matched.containsKey(event.sqlId())) {
            return null;
        }
        return ir.edges().stream()
                .filter(e -> event.sqlId().equals(e.sqlId()))
                .map(Edge::step)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private int positionOf(TraceEvent write, List<TraceEvent> ordered,
                           Map<String, List<Integer>> matched) {
        int position = 0;
        for (TraceEvent event : ordered) {
            if (matched.containsKey(event.sqlId())
                    || (event.isWrite() && !isDictionaryObject(event.target()))) {
                position++;
            }
            if (event == write) {
                return position;
            }
        }
        return position;
    }

    private Set<String> sqlIdsOf(Ir ir) {
        Set<String> ids = new LinkedHashSet<>();
        ir.edges().stream().map(Edge::sqlId).filter(java.util.Objects::nonNull).forEach(ids::add);
        return ids;
    }

    private Meta withTraceSource(Meta meta, int notExecuted, int unattributed) {
        return new Meta(meta.schemaVersion(), meta.db(), meta.entryPoint(), meta.staticSource(),
                new TraceSource(true, capturedAt, scenario,
                        notExecuted == 0 ? null : notExecuted,
                        unattributed == 0 ? null : unattributed));
    }

    private Edge.EdgeBuilder rebuild(Edge edge) {
        return Edge.builder()
                .id(edge.id())
                .type(edge.type())
                .op(edge.op())
                .from(edge.from())
                .to(edge.to())
                .step(edge.step())
                .line(edge.line())
                .sqlId(edge.sqlId())
                .confidence(edge.confidence())
                .resolvedVia(edge.resolvedVia())
                .reachability(edge.reachability())
                .guard(edge.guard())
                .rawText(edge.rawText())
                .viaTrigger(edge.viaTrigger())
                .resolves(edge.resolves());
    }
}
