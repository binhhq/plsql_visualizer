package com.example.plsqlvisualizer.extract;

import com.example.plsqlvisualizer.db.DictionaryClient;
import com.example.plsqlvisualizer.db.IdentifierRow;
import com.example.plsqlvisualizer.db.ObjectRow;
import com.example.plsqlvisualizer.db.RawCall;
import com.example.plsqlvisualizer.db.RawWrite;
import com.example.plsqlvisualizer.db.StatementRow;
import com.example.plsqlvisualizer.db.SynonymRow;
import com.example.plsqlvisualizer.db.TriggerRow;
import com.example.plsqlvisualizer.db.UnitKey;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Everything the extractors need, pulled from the dictionary once and held in
 * memory. Two reasons this exists rather than each extractor querying:
 * resolving a statement to its enclosing subprogram means walking the
 * usage-context tree, which is a per-row lookup that would otherwise be a
 * per-row round trip.
 */
public class DictionarySnapshot {

    private static final Logger LOG = LoggerFactory.getLogger(DictionarySnapshot.class);

    private final String schema;
    private final List<RawWrite> writes;
    private final List<RawCall> calls;
    private final List<StatementRow> statements;
    private final List<SynonymRow> synonyms;
    private final List<TriggerRow> triggers;
    private final List<ObjectRow> objects;
    private final Map<UnitKey, List<String>> source;

    /** (unit, usageId) → identifier, for the upward walk. */
    private final Map<UnitKey, Map<Long, IdentifierRow>> identifiersByUsageId = new HashMap<>();

    /** Memoised results of {@link #scopeOf} — the same context is hit repeatedly. */
    private final Map<UnitKey, Map<Long, EnclosingScope>> scopeCache = new HashMap<>();

    /** Subprogram definitions per unit, ordered by line, for {@link #subprogramRange}. */
    private final Map<UnitKey, List<IdentifierRow>> subprogramsByUnit = new HashMap<>();

    private final Map<UnitKey, ReachabilityAnalyzer> reachabilityCache = new HashMap<>();

    public DictionarySnapshot(DictionaryClient client) throws SQLException {
        this(client, null);
    }

    /**
     * A snapshot for a scoped extraction: like the constructor below, but the
     * object list is narrowed too.
     *
     * <p>That list becomes {@code meta.static_source.units}. A refresh needs it
     * whole — it is the record every later staleness check is compared against —
     * but a scoped graph is not a claim about the schema, and shipping a hundred
     * thousand unit names inside a graph of six would dwarf the graph itself.
     */
    public static DictionarySnapshot scoped(DictionaryClient client, Collection<UnitKey> units)
            throws SQLException {
        return new DictionarySnapshot(client, units, true);
    }

    /**
     * A snapshot narrowed to a few units, for the incremental refresh (design.md §7).
     *
     * <p>Only the per-unit views are restricted. Synonyms and triggers are
     * schema-wide resolution data and cheap to re-read, and the object list has to
     * stay complete because it becomes the IR's freshness record for
     * <em>every</em> unit, not just the rebuilt ones.
     *
     * <p>Safe to narrow because no pass reads anything outside the unit that owns
     * the edge it emits: a callee's name and type arrive on the call row itself,
     * from the signature join, not from a lookup here.
     *
     * @param units units to restrict to; null means the whole schema, and an empty
     *        set means no units at all — what a refresh with only prunes to do asks for
     */
    public DictionarySnapshot(DictionaryClient client, Collection<UnitKey> units)
            throws SQLException {
        this(client, units, false);
    }

    private DictionarySnapshot(DictionaryClient client, Collection<UnitKey> units,
                               boolean scopedObjects) throws SQLException {
        this.schema = client.schema();
        // Timed one query at a time. Unrestricted, these read USER_IDENTIFIERS and
        // USER_STATEMENTS across the whole schema, which on a large one takes long
        // enough that a silent run is indistinguishable from a hung one — and
        // which of them is slow is the first thing worth knowing.
        this.writes = timed("writes", () -> client.writes(units));
        this.calls = timed("calls", () -> client.calls(units));
        this.statements = timed("statements", () -> client.statements(units));
        // Synonyms follow the restriction: only the ones these units actually name
        // can resolve any of their write targets, and a production database
        // routinely carries tens of thousands of PUBLIC synonyms that no graph of
        // a few packages will ever mention.
        this.synonyms = timed("synonyms", () -> client.synonyms(units));
        // Only ever used as a name → triggering-table lookup for triggers whose
        // writes are already in this snapshot, so restricting it to the triggers
        // in scope is the same map, minus a full scan of USER_TRIGGERS.
        this.triggers = timed("triggers", () -> units == null
                ? client.triggers()
                : client.triggersNamed(units.stream()
                        .filter(UnitKey::isTrigger)
                        .map(UnitKey::name)
                        .toList()));
        this.objects = timed("objects", () -> client.objects(scopedObjects ? units : null));
        this.source = timedByUnit("source", () -> client.source(units));

        for (IdentifierRow row : timed("identifiers", () -> client.identifiers(units))) {
            identifiersByUsageId
                    .computeIfAbsent(row.unit(), k -> new HashMap<>())
                    .put(row.usageId(), row);
            if (row.isSubprogramDefinition() && "DEFINITION".equals(row.usage())) {
                subprogramsByUnit.computeIfAbsent(row.unit(), k -> new ArrayList<>()).add(row);
            }
        }
        subprogramsByUnit.values()
                .forEach(rows -> rows.sort(Comparator.comparingInt(IdentifierRow::line)));
    }

    /** A dictionary read that may fail the way JDBC fails. */
    @FunctionalInterface
    private interface Read<T> {
        T get() throws SQLException;
    }

    private static <T> List<T> timed(String what, Read<List<T>> read) throws SQLException {
        long started = System.nanoTime();
        List<T> rows = read.get();
        LOG.info("  {}: {} rows in {} ms", what, rows.size(),
                (System.nanoTime() - started) / 1_000_000);
        return rows;
    }

    private static <K, V> Map<K, V> timedByUnit(String what, Read<Map<K, V>> read) throws SQLException {
        long started = System.nanoTime();
        Map<K, V> rows = read.get();
        LOG.info("  {}: {} unit(s) in {} ms", what, rows.size(),
                (System.nanoTime() - started) / 1_000_000);
        return rows;
    }

    public String schema() {
        return schema;
    }

    public List<RawWrite> writes() {
        return writes;
    }

    public List<RawCall> calls() {
        return calls;
    }

    public List<StatementRow> statements() {
        return statements;
    }

    public List<SynonymRow> synonyms() {
        return synonyms;
    }

    public List<TriggerRow> triggers() {
        return triggers;
    }

    public List<ObjectRow> objects() {
        return objects;
    }

    public List<String> sourceOf(UnitKey unit) {
        return source.getOrDefault(unit, List.of());
    }

    /** Reachability analysis for a unit, built from its source text once. */
    public ReachabilityAnalyzer reachability(UnitKey unit) {
        return reachabilityCache.computeIfAbsent(unit, u -> new ReachabilityAnalyzer(sourceOf(u)));
    }

    /**
     * The 1-based, inclusive line range a subprogram occupies, taken as "from its
     * definition to just before the next one". Rough, but enough to keep a
     * source-text search from wandering into a neighbouring procedure.
     */
    public int[] subprogramRange(UnitKey unit, String subprogram) {
        List<IdentifierRow> rows = subprogramsByUnit.getOrDefault(unit, List.of());
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).name().equals(subprogram)) {
                int start = rows.get(i).line();
                int end = i + 1 < rows.size() ? rows.get(i + 1).line() - 1 : Integer.MAX_VALUE;
                return new int[] {start, end};
            }
        }
        return new int[] {1, Integer.MAX_VALUE};
    }

    /**
     * Walks from {@code contextId} up the usage-context chain to the enclosing
     * subprogram.
     *
     * <p>The chain length varies: a top-level statement's context is the
     * subprogram itself, a call's context is the package reference in front of
     * it, and a statement inside a FOR loop hangs off the loop's ITERATOR. The
     * walk handles all three by simply climbing until it finds a definition.
     */
    public EnclosingScope scopeOf(UnitKey unit, long contextId) {
        Map<Long, EnclosingScope> cache = scopeCache.computeIfAbsent(unit, k -> new HashMap<>());
        EnclosingScope cached = cache.get(contextId);
        if (cached != null) {
            return cached;
        }

        Map<Long, IdentifierRow> byId = identifiersByUsageId.getOrDefault(unit, Map.of());
        boolean sawIterator = false;
        long current = contextId;
        // usage_context_id 0 means "top of the unit"; the guard also stops a
        // malformed cycle from spinning forever.
        for (int hops = 0; current != 0 && hops < 100; hops++) {
            IdentifierRow row = byId.get(current);
            if (row == null) {
                break;
            }
            if (row.isIterator()) {
                sawIterator = true;
            }
            if (row.isSubprogramDefinition() || row.isTriggerDefinition()) {
                EnclosingScope scope = new EnclosingScope(row.name(), sawIterator);
                cache.put(contextId, scope);
                return scope;
            }
            current = row.usageContextId();
        }

        EnclosingScope scope = EnclosingScope.unknown();
        cache.put(contextId, scope);
        return scope;
    }
}
