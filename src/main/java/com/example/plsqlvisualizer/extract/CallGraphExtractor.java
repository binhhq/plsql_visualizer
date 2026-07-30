package com.example.plsqlvisualizer.extract;

import com.example.plsqlvisualizer.db.RawCall;
import com.example.plsqlvisualizer.db.UnitKey;
import com.example.plsqlvisualizer.model.Confidence;
import com.example.plsqlvisualizer.model.Edge;
import com.example.plsqlvisualizer.model.EdgeType;
import com.example.plsqlvisualizer.model.Node;
import com.example.plsqlvisualizer.model.Provenance;
import com.example.plsqlvisualizer.model.Reachability;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Emits caller → callee edges (design.md §4.1). PL/SQL calls are statically
 * named, so resolving the callee by its globally unique {@code SIGNATURE} is
 * exact — there is no dynamic dispatch to miss.
 *
 * <p>Cycles are expected and fine; this pass produces a directed graph, not a
 * DAG, and the traversal that consumes it is cycle-safe.
 *
 * <p>The signature join matches a callee's package spec <em>and</em> its body,
 * so each call arrives twice. They are collapsed here, preferring the body —
 * that is where the code being called actually lives.
 */
public class CallGraphExtractor {

    /** One logical call: everything that makes two dictionary rows the same call. */
    private record CallKey(UnitKey callerUnit, String callerSubprogram,
                           String calleeUnit, String calleeSubprogram, int line) {
    }

    private final DictionarySnapshot snapshot;

    public CallGraphExtractor(DictionarySnapshot snapshot) {
        this.snapshot = snapshot;
    }

    public void extractInto(GraphAccumulator graph) {
        Map<CallKey, RawCall> deduped = new LinkedHashMap<>();

        for (RawCall call : snapshot.calls()) {
            EnclosingScope scope = snapshot.scopeOf(call.callerUnit(), call.callContextId());
            CallKey key = new CallKey(call.callerUnit(), scope.subprogram(),
                    call.calleeUnit(), call.calleeSubprogram(), call.line());

            RawCall existing = deduped.get(key);
            if (existing == null || prefer(call, existing)) {
                deduped.put(key, call);
            }
        }

        deduped.forEach((key, call) -> emit(graph, key, call));
    }

    /** The body describes where the callee's code lives; the spec only declares it. */
    private boolean prefer(RawCall candidate, RawCall existing) {
        return "PACKAGE BODY".equals(candidate.calleeUnitType())
                && !"PACKAGE BODY".equals(existing.calleeUnitType());
    }

    private void emit(GraphAccumulator graph, CallKey key, RawCall call) {
        UnitKey callerUnit = key.callerUnit();
        EnclosingScope scope = snapshot.scopeOf(callerUnit, call.callContextId());

        Node from = graph.node(Node.programUnit(
                snapshot.schema(), callerUnit.name(), key.callerSubprogram(), callerUnit.type()));
        Node to = graph.node(Node.programUnit(
                snapshot.schema(), key.calleeUnit(), key.calleeSubprogram(), call.calleeUnitType()));

        ReachabilityAnalyzer analyzer = snapshot.reachability(callerUnit);
        Reachability reachability = analyzer.at(call.line(), scope.insideIterator());
        String guard = reachability == Reachability.BRANCH_CONDITIONAL
                ? analyzer.guardAt(call.line())
                : null;

        graph.edge(Edge.builder()
                .type(EdgeType.CALL)
                .from(from.id())
                .to(to.id())
                .line(call.line())
                .confidence(Confidence.RESOLVED)
                .reachability(reachability)
                .guard(guard)
                .provenance(List.of(Provenance.STATIC))
                .build());
    }
}
