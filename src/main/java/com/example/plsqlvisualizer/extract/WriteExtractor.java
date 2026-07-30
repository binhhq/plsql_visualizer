package com.example.plsqlvisualizer.extract;

import com.example.plsqlvisualizer.db.RawWrite;
import com.example.plsqlvisualizer.db.UnitKey;
import com.example.plsqlvisualizer.model.Confidence;
import com.example.plsqlvisualizer.model.Edge;
import com.example.plsqlvisualizer.model.EdgeType;
import com.example.plsqlvisualizer.model.Node;
import com.example.plsqlvisualizer.model.Op;
import com.example.plsqlvisualizer.model.Provenance;
import com.example.plsqlvisualizer.model.Reachability;
import java.util.List;

/**
 * Emits one write edge per DML statement: which subprogram mutates which table
 * (design.md §4.2–§4.4).
 *
 * <p>Triggers are skipped here — their writes belong to the table that fires
 * them, not to a caller, and {@link TriggerExtractor} owns that.
 *
 * <p>A target that will not resolve is never dropped and never guessed: it
 * becomes an edge to the {@code __UNKNOWN__} sentinel, flagged
 * {@code dynamic-unknown}.
 */
public class WriteExtractor {

    private final DictionarySnapshot snapshot;
    private final SynonymResolver resolver;

    public WriteExtractor(DictionarySnapshot snapshot, SynonymResolver resolver) {
        this.snapshot = snapshot;
        this.resolver = resolver;
    }

    public void extractInto(GraphAccumulator graph) {
        for (RawWrite write : snapshot.writes()) {
            if (write.unit().isTrigger()) {
                continue;
            }
            emit(graph, write);
        }
    }

    private void emit(GraphAccumulator graph, RawWrite write) {
        UnitKey unit = write.unit();
        EnclosingScope scope = snapshot.scopeOf(unit, write.statementContextId());

        Node from = graph.node(Node.programUnit(
                snapshot.schema(), unit.name(), scope.subprogram(), unit.type()));

        ReachabilityAnalyzer reachabilityAnalyzer = snapshot.reachability(unit);
        Reachability reachability = reachabilityAnalyzer.at(write.line(), scope.insideIterator());
        String guard = reachability == Reachability.BRANCH_CONDITIONAL
                ? reachabilityAnalyzer.guardAt(write.line())
                : null;

        Edge.EdgeBuilder edge = Edge.builder()
                .type(EdgeType.WRITE)
                .op(Op.fromStatementType(write.op()))
                .from(from.id())
                .line(write.line())
                .sqlId(write.sqlId())
                .reachability(reachability)
                .guard(guard)
                .provenance(List.of(Provenance.STATIC));

        SynonymResolver.Target target = resolver.resolve(write.targetObject(), write.targetKind());
        if (target == null) {
            // The statement writes something we cannot name. Say so rather than
            // dropping the write (design.md §2.3, §4.4).
            graph.node(Node.unknown());
            graph.edge(edge
                    .to(Node.UNKNOWN_ID)
                    .confidence(Confidence.DYNAMIC_UNKNOWN)
                    .rawText(sourceLine(unit, write.line()))
                    .build());
            return;
        }

        Node to = graph.node(Node.table(target.owner(), target.name()));
        graph.edge(edge
                .to(to.id())
                .confidence(Confidence.RESOLVED)
                .resolvedVia(target.via())
                .build());
    }

    private String sourceLine(UnitKey unit, int line) {
        List<String> lines = snapshot.sourceOf(unit);
        return line >= 1 && line <= lines.size() ? lines.get(line - 1).trim() : null;
    }
}
