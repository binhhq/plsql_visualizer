package com.example.plsqlvisualizer.freshness;

import com.example.plsqlvisualizer.db.DictionaryClient;
import com.example.plsqlvisualizer.db.UnitKey;
import com.example.plsqlvisualizer.extract.DictionarySnapshot;
import com.example.plsqlvisualizer.extract.GraphAccumulator;
import com.example.plsqlvisualizer.graph.IrBuilder;
import com.example.plsqlvisualizer.model.Edge;
import com.example.plsqlvisualizer.model.Ir;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Brings an existing IR back up to date, reading only what changed — the other
 * half of design.md §7, and the reason the staleness check is worth running.
 *
 * <p>The result is defined to equal a full extraction. Incremental is an
 * optimisation on how much of the dictionary gets scanned, never a different
 * answer, so anything that would make the two diverge falls back to a full
 * extraction and says so rather than quietly producing a cheaper, wronger graph.
 */
public class IrRefresher {

    private final DictionaryClient client;

    public IrRefresher(DictionaryClient client) {
        this.client = client;
    }

    /**
     * @param ir the refreshed IR — the previous instance itself when already fresh
     * @param reextracted units read from the dictionary again
     * @param pruned units dropped from the IR because the schema no longer has them
     * @param fullRebuild true when the incremental path was abandoned
     * @param fallbackReason why, when {@code fullRebuild} was forced; null otherwise
     */
    public record Result(Ir ir, StalenessReport report, List<UnitKey> reextracted,
                         List<UnitKey> pruned, boolean fullRebuild, String fallbackReason) {

        public boolean changedAnything() {
            return !report.isFresh();
        }
    }

    /**
     * @param entryPoint entry point for the rebuilt IR, or null to keep the one the
     *        previous IR was built with — a refresh should not silently re-root the walk
     */
    public Result refresh(Ir previous, String entryPoint) throws SQLException {
        StalenessReport report = new StalenessChecker(client).check(previous);
        String walkFrom = entryPoint != null ? entryPoint : recordedEntryPoint(previous);

        if (report.isFresh()) {
            return new Result(previous, report, List.of(), List.of(), false, null);
        }

        IrSplicer splicer = new IrSplicer(previous);
        List<Edge> unowned = splicer.unownedEdges();
        if (!unowned.isEmpty()) {
            String reason = "%d edge(s) in the existing IR do not record which unit produced them"
                    .formatted(unowned.size());
            return new Result(fullExtract(walkFrom), report, allUnits(report), List.of(),
                    true, reason);
        }

        List<UnitKey> reextract = report.needsReextraction();
        IrBuilder builder = new IrBuilder(new DictionarySnapshot(client, reextract));
        GraphAccumulator fresh = builder.extract();

        IrSplicer.Merged merged = splicer.merge(allUnits(report), fresh.nodes(), fresh.edges());
        Ir ir = builder.assemble(merged.nodes(), merged.edges(), walkFrom);

        return new Result(ir, report, reextract, report.removed(), false, null);
    }

    private Ir fullExtract(String entryPoint) throws SQLException {
        return new IrBuilder(new DictionarySnapshot(client)).build(entryPoint);
    }

    /** Every unit whose old edges are no longer trustworthy, rebuilt or pruned. */
    private static List<UnitKey> allUnits(StalenessReport report) {
        Set<UnitKey> units = new LinkedHashSet<>(
                Stream.concat(report.needsReextraction().stream(), report.removed().stream())
                        .toList());
        return new ArrayList<>(units);
    }

    private static String recordedEntryPoint(Ir previous) {
        return previous.meta() == null ? null : previous.meta().entryPoint();
    }
}
