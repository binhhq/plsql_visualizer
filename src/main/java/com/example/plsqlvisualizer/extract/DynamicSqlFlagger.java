package com.example.plsqlvisualizer.extract;

import com.example.plsqlvisualizer.db.StatementRow;
import com.example.plsqlvisualizer.db.UnitKey;
import com.example.plsqlvisualizer.model.Confidence;
import com.example.plsqlvisualizer.model.Edge;
import com.example.plsqlvisualizer.model.EdgeType;
import com.example.plsqlvisualizer.model.Node;
import com.example.plsqlvisualizer.model.Op;
import com.example.plsqlvisualizer.model.Provenance;
import com.example.plsqlvisualizer.model.Reachability;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns {@code EXECUTE IMMEDIATE} / {@code DBMS_SQL} into edges to the
 * {@code __UNKNOWN__} sentinel (design.md §4.4).
 *
 * <p>The target table is a runtime string. Static analysis cannot know it, and
 * this class does not try: the edge always points at the sentinel, so a reader
 * sees "a write happens here and we cannot say where" rather than a plausible
 * lie or a silent gap.
 *
 * <p>The <em>operation</em> is a softer case. It is often visible in the
 * literal being concatenated, and knowing an unknown table is being INSERTed
 * into is more useful than knowing nothing. So we read it off the source when
 * exactly one DML keyword starts a string literal in the subprogram, and leave
 * it null otherwise. The target stays unknown either way.
 */
public class DynamicSqlFlagger {

    /** A string literal whose first word is a DML verb, e.g. {@code 'INSERT INTO ' || t}. */
    private static final Pattern DML_LITERAL =
            Pattern.compile("'\\s*(INSERT|UPDATE|DELETE|MERGE)\\b", Pattern.CASE_INSENSITIVE);

    private final DictionarySnapshot snapshot;

    public DynamicSqlFlagger(DictionarySnapshot snapshot) {
        this.snapshot = snapshot;
    }

    public void extractInto(GraphAccumulator graph) {
        for (StatementRow statement : snapshot.statements()) {
            if (!statement.isDynamic() || statement.unit().isTrigger()) {
                continue;
            }
            emit(graph, statement);
        }
    }

    private void emit(GraphAccumulator graph, StatementRow statement) {
        UnitKey unit = statement.unit();
        EnclosingScope scope = snapshot.scopeOf(unit, statement.usageContextId());

        Node from = graph.node(Node.programUnit(
                snapshot.schema(), unit.name(), scope.subprogram(), unit.type()));
        graph.node(Node.unknown());

        ReachabilityAnalyzer analyzer = snapshot.reachability(unit);
        Reachability reachability = analyzer.at(statement.line(), scope.insideIterator());
        String guard = reachability == Reachability.BRANCH_CONDITIONAL
                ? analyzer.guardAt(statement.line())
                : null;

        DynamicHint hint = inspectSource(unit, scope.subprogram());

        graph.edge(Edge.builder()
                .type(EdgeType.WRITE)
                .op(hint.op())
                .from(from.id())
                .to(Node.UNKNOWN_ID)
                .line(statement.line())
                .sqlId(statement.sqlId())
                .confidence(Confidence.DYNAMIC_UNKNOWN)
                .reachability(reachability)
                .guard(guard)
                .rawText(hint.rawText())
                .provenance(List.of(Provenance.STATIC))
                .build());
    }

    /** What, if anything, the surrounding source reveals about the dynamic statement. */
    private record DynamicHint(Op op, String rawText) {
        static DynamicHint empty() {
            return new DynamicHint(null, null);
        }
    }

    private DynamicHint inspectSource(UnitKey unit, String subprogram) {
        List<String> lines = snapshot.sourceOf(unit);
        int[] range = snapshot.subprogramRange(unit, subprogram);

        Set<Op> ops = new LinkedHashSet<>();
        String rawText = null;

        for (int line = range[0]; line <= Math.min(range[1], lines.size()); line++) {
            String text = lines.get(line - 1);
            Matcher matcher = DML_LITERAL.matcher(text);
            while (matcher.find()) {
                Op op = Op.fromStatementType(matcher.group(1).toUpperCase(Locale.ROOT));
                if (op != null && ops.add(op) && rawText == null) {
                    rawText = text.trim();
                }
            }
        }

        // More than one verb in scope means we would be picking; stay quiet.
        return ops.size() == 1 ? new DynamicHint(ops.iterator().next(), rawText) : DynamicHint.empty();
    }
}
