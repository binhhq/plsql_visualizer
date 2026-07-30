package com.example.plsqlvisualizer.extract;

import com.example.plsqlvisualizer.db.RawWrite;
import com.example.plsqlvisualizer.db.TriggerRow;
import com.example.plsqlvisualizer.model.Confidence;
import com.example.plsqlvisualizer.model.Edge;
import com.example.plsqlvisualizer.model.EdgeType;
import com.example.plsqlvisualizer.model.Node;
import com.example.plsqlvisualizer.model.Op;
import com.example.plsqlvisualizer.model.Provenance;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the writes nobody wrote (design.md §4.5).
 *
 * <p>When a procedure INSERTs into {@code ORDERS} and a trigger on that table
 * writes {@code ORDER_AUDIT}, the second write appears nowhere in the
 * procedure's statements. Reading only the procedure, it is invisible. This
 * pass walks triggers separately and emits
 * {@code table --op--> table} edges whose source is the <em>triggering table</em>,
 * not the caller, because that is what actually causes them.
 */
public class TriggerExtractor {

    private final DictionarySnapshot snapshot;
    private final SynonymResolver resolver;

    public TriggerExtractor(DictionarySnapshot snapshot, SynonymResolver resolver) {
        this.snapshot = snapshot;
        this.resolver = resolver;
    }

    public void extractInto(GraphAccumulator graph) {
        Map<String, TriggerRow> byName = new HashMap<>();
        for (TriggerRow trigger : snapshot.triggers()) {
            byName.put(trigger.triggerName(), trigger);
        }

        for (RawWrite write : snapshot.writes()) {
            if (!write.unit().isTrigger()) {
                continue;
            }
            TriggerRow trigger = byName.get(write.unit().name());
            if (trigger == null) {
                continue;
            }
            emit(graph, trigger, write);
        }
    }

    private void emit(GraphAccumulator graph, TriggerRow trigger, RawWrite write) {
        Node from = graph.node(Node.table(snapshot.schema(), trigger.tableName()));

        Edge.EdgeBuilder edge = Edge.builder()
                .type(EdgeType.WRITE)
                .op(Op.fromStatementType(write.op()))
                .from(from.id())
                .line(write.line())
                .sqlId(write.sqlId())
                .confidence(Confidence.TRIGGER_INDUCED)
                .viaTrigger(trigger.triggerName())
                .provenance(List.of(Provenance.STATIC));

        SynonymResolver.Target target = resolver.resolve(write.targetObject(), write.targetKind());
        if (target == null) {
            graph.node(Node.unknown());
            graph.edge(edge.to(Node.UNKNOWN_ID).build());
            return;
        }

        Node to = graph.node(Node.table(target.owner(), target.name()));
        graph.edge(edge.to(to.id()).resolvedVia(target.via()).build());
    }
}
