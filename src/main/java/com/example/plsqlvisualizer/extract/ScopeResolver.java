package com.example.plsqlvisualizer.extract;

import com.example.plsqlvisualizer.db.DictionaryClient;
import com.example.plsqlvisualizer.db.RawCall;
import com.example.plsqlvisualizer.db.SynonymRow;
import com.example.plsqlvisualizer.db.TriggerRow;
import com.example.plsqlvisualizer.db.UnitKey;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Works out the smallest set of units that can answer "show me what
 * {@code PKG_ORDER} does" — the scoping pass that makes a large schema usable.
 *
 * <p>Reading the whole dictionary is only tractable on a toy schema. On a
 * production one, {@code USER_IDENTIFIERS} alone runs to millions of rows and the
 * unrestricted read cannot finish in a time anybody will wait for. So nothing is
 * read until a unit has been named, and then only what that name reaches:
 *
 * <ol>
 *   <li>the named unit itself — every analysable object type carrying the name,
 *       so a package brings its body;</li>
 *   <li>what it calls, breadth-first, to {@code depth} hops. Each hop is one
 *       bounded query, not a wider scan;</li>
 *   <li>the triggers standing on the tables all of that writes — and, because a
 *       trigger's own writes can fire further triggers, the same step repeated
 *       until it stops finding new ones.</li>
 * </ol>
 *
 * <p>Step 3 is not an optimisation to skip. A trigger's writes appear in no
 * procedure's source, which is precisely what this tool exists to surface
 * (design.md §4.5) — but only the triggers on tables inside the scope can
 * contribute an edge to it, so taking every trigger in the schema, as a
 * whole-schema read must, is both wrong and ruinously expensive.
 */
public final class ScopeResolver {

    private static final Logger LOG = LoggerFactory.getLogger(ScopeResolver.class);

    /**
     * How many times to chase triggers firing triggers. Cascades deeper than this
     * exist but are rare, and an unbounded loop here would hand a mutually
     * recursive pair of triggers the power to hang the request.
     */
    private static final int TRIGGER_ROUNDS = 4;

    private ScopeResolver() {
    }

    /**
     * The units a scoped extraction should read.
     *
     * @param truncated the unit cap was hit, so the graph is a prefix of what the
     *        seed reaches rather than all of it — the renderer says so instead of
     *        presenting a partial graph as complete
     */
    public record Scope(String seed, int depth, List<UnitKey> units, boolean truncated) {
    }

    /**
     * @param seedName unit name as typed; matched case-insensitively against
     *        {@code USER_OBJECTS}
     * @param depth call-graph hops to follow from the seed; 0 reads the seed alone
     * @param maxUnits stop expanding once the scope reaches this many units
     * @throws IllegalArgumentException when no analysable unit carries that name
     */
    public static Scope resolve(DictionaryClient client, String seedName, int depth, int maxUnits)
            throws SQLException {
        String name = seedName.trim().toUpperCase(Locale.ROOT);
        List<UnitKey> seeds = client.unitsNamed(List.of(name));
        if (seeds.isEmpty()) {
            throw new IllegalArgumentException(
                    "No package body, procedure, function or trigger named " + name
                            + " in schema " + client.schema());
        }

        Set<UnitKey> scope = new LinkedHashSet<>(seeds);
        boolean truncated = followCalls(client, scope, seeds, depth, maxUnits);
        truncated |= followTriggers(client, scope, maxUnits);

        List<UnitKey> units = List.copyOf(scope);
        LOG.info("Scope for {} (depth {}): {} unit(s){}", name, depth, units.size(),
                truncated ? " — truncated at the unit cap" : "");
        return new Scope(name, depth, units, truncated);
    }

    /** Breadth-first over the call graph. Returns whether the cap cut it short. */
    private static boolean followCalls(DictionaryClient client, Set<UnitKey> scope,
                                       List<UnitKey> seeds, int depth, int maxUnits)
            throws SQLException {
        List<UnitKey> frontier = seeds;
        for (int hop = 0; hop < depth && !frontier.isEmpty(); hop++) {
            if (scope.size() >= maxUnits) {
                return true;
            }
            Set<String> calleeNames = client.calls(frontier).stream()
                    .map(RawCall::calleeUnit)
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            // Back through USER_OBJECTS rather than trusting the callee row: a
            // call resolves to whichever unit declares it, which for a package
            // call is the spec — and a spec holds no statements. The name is what
            // carries over; the analysable object types behind it are the answer.
            frontier = newUnits(scope, client.unitsNamed(calleeNames), maxUnits);
            scope.addAll(frontier);
        }
        return scope.size() >= maxUnits;
    }

    /** Triggers on the tables the scope writes, then triggers on what those write. */
    private static boolean followTriggers(DictionaryClient client, Set<UnitKey> scope, int maxUnits)
            throws SQLException {
        Collection<UnitKey> writers = List.copyOf(scope);
        for (int round = 0; round < TRIGGER_ROUNDS && !writers.isEmpty(); round++) {
            if (scope.size() >= maxUnits) {
                return true;
            }
            Set<String> tables = tablesWrittenBy(client, writers);
            if (tables.isEmpty()) {
                return false;
            }
            List<String> triggerNames = client.triggersOn(tables).stream()
                    .map(TriggerRow::triggerName)
                    .distinct()
                    .toList();
            List<UnitKey> found = client.unitsNamed(triggerNames).stream()
                    .filter(UnitKey::isTrigger)
                    .toList();

            writers = newUnits(scope, found, maxUnits);
            scope.addAll(writers);
        }
        return scope.size() >= maxUnits;
    }

    /**
     * Tables these units write, with synonyms followed. A trigger stands on a
     * base table, so a write aimed at a synonym only finds its trigger once the
     * synonym has been resolved.
     */
    private static Set<String> tablesWrittenBy(DictionaryClient client, Collection<UnitKey> units)
            throws SQLException {
        List<String> targets = client.writeTargetNames(units);
        if (targets.isEmpty()) {
            return Set.of();
        }
        Map<String, SynonymRow> synonyms = client.synonyms(units).stream()
                .collect(Collectors.toMap(SynonymRow::synonymName, Function.identity(),
                        // The schema's own synonym shadows a PUBLIC one of the same
                        // name, and USER rows come back first — keep the first seen.
                        (first, ignored) -> first));

        Set<String> tables = new LinkedHashSet<>();
        for (String target : targets) {
            SynonymRow synonym = synonyms.get(target);
            tables.add(synonym != null && synonym.tableName() != null
                    ? synonym.tableName()
                    : target);
        }
        return tables;
    }

    /** The candidates not already in scope, cut off at the cap. */
    private static List<UnitKey> newUnits(Set<UnitKey> scope, List<UnitKey> candidates,
                                          int maxUnits) {
        List<UnitKey> fresh = new ArrayList<>();
        for (UnitKey candidate : candidates) {
            if (scope.size() + fresh.size() >= maxUnits) {
                break;
            }
            if (!scope.contains(candidate)) {
                fresh.add(candidate);
            }
        }
        return fresh;
    }
}
