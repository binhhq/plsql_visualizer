# PL/SQL Data-Flow Visualizer — Design

> Spec for implementation by Claude Code. Target DB: **Oracle 19c**.
> The UI/renderer is delegated to **Claude Design** — see §8. This doc owns
> the extractor, the IR contract, and the freshness model.

---

## 1. Goal

The legacy core (NEWFO) is a large, complex set of Oracle **PL/SQL packages**
whose procedures `INSERT`/`UPDATE` many tables and call each other across
packages. We need to **see the sequence of table mutations** — step by step —
to understand and re-implement that logic in the new event-sourced core.

Output: a graph the user can **step through** — node = table, edge = a write
(or a call), revealed in execution order.

### Non-goals
- Not a full PL/SQL static analyzer / linter.
- Not a runtime profiler. We *optionally* consume a runtime trace, we don't build one.
- Not a schema/ERD tool. Edges are **data-flow**, not FK relationships.

---

## 2. Core principles (these shape every decision)

1. **Oracle already parsed the code — we do NOT write a PL/SQL parser.**
   PL/Scope (compile-time) exposes every SQL statement and identifier in the
   data dictionary. The extractor is a **thin JDBC client** that runs a handful
   of recursive SQL queries. Writing an ANTLR grammar for PL/SQL is the trap;
   do not do it.

2. **Two sources, one IR.**
   - **Static (PL/Scope)** = *where control CAN go* — a superset in lexical
     order. Complete-ish, but not true runtime order.
   - **Runtime trace (optional)** = *where control DID go* — true order for one
     execution, but only the path that ran.
   The IR carries both; the renderer shows which is which.

3. **Honesty over completeness.** Anything static cannot resolve — dynamic SQL
   targets, trigger-induced writes, conditional branches — is **flagged, never
   swallowed**. A graph that looks complete but silently dropped a write is
   worse than one that says "unknown here".

---

## 3. Architecture

```
Oracle 19c legacy packages
        │  (compiled with PL/Scope on)
        ▼
Data dictionary  ─ ALL_STATEMENTS · ALL_IDENTIFIERS · ALL_DEPENDENCIES · ALL_SYNONYMS · ALL_TRIGGERS
        │
        ├────────────── STATIC lane (Java + JDBC, thin) ───────────────┐
        │   call graph · writes · synonym/view resolve · trigger pass   │
        │   build graph (JGraphT) · assign step ordinal                 │
        │                                                               ▼
   RUNTIME TRACE lane (optional) ───────────────────────────────►  JSON IR  ──►  SVG renderer
   event 10046 / DBMS_HPROF → true order overlay                 (contract)     (Claude Design)
```

See `design-architecture.svg` (delivered alongside) for the annotated diagram.

---

## 4. Static extraction (the main lane)

### 4.0 Prerequisite — turn PL/Scope on the RIGHT way
PL/Scope collects data **at compile time**, controlled by `PLSCOPE_SETTINGS`.
Default is `IDENTIFIERS:NONE` and the value is **stored per library unit**.

**The silent-wipe trap:** a normal deploy (`CREATE OR REPLACE`) takes the
*session's* current setting. If that session doesn't set PL/Scope, the object is
re-stamped `NONE` and its PL/Scope data **disappears with no warning**.

Fix on the analysis instance — set it as the system default so every compile
(including deploys and Oracle's own invalidation-cascade recompiles) self-collects:

```sql
ALTER SYSTEM SET PLSCOPE_SETTINGS = 'IDENTIFIERS:ALL, STATEMENTS:ALL' SCOPE = BOTH;
```

Do this on a **dev/test copy**, never prod (compile is slower; data lands in SYSAUX).

### 4.1 Call graph
`ALL_IDENTIFIERS` where `USAGE='CALL'` gives caller→callee. PL/SQL calls are
statically named (no dynamic dispatch), so cross-package resolves cleanly.
Resolve the callee to its **owning package** by joining on `SIGNATURE`
(globally unique) to the callee's `DECLARATION`/`DEFINITION`. Cycles are
allowed — treat the result as a directed graph, not a DAG.

### 4.2 Writes (proc → table)
Join `ALL_STATEMENTS` (`TYPE IN ('INSERT','UPDATE','DELETE','MERGE')`) to the
table identifier in `ALL_IDENTIFIERS` via the hierarchy
(`usage_context_id` → `usage_id`). Yields `(unit, line, op, target)`.
Reference SQL is in `sql/05_extraction_queries.sql`.

### 4.3 Target resolution
The target from 4.2 may be a `TABLE`, `VIEW`, or `SYNONYM`.
- `SYNONYM` → resolve to base object via `ALL_SYNONYMS` → mark `resolved_via = "synonym"`.
- `VIEW` → keep the view as target, mark `resolved_via = "view"` (writing through a
  view / INSTEAD OF trigger is a separate hop; flag, don't chase blindly).

### 4.4 Dynamic SQL — flag, don't guess
`EXECUTE IMMEDIATE` / `DBMS_SQL` targets are runtime strings. Static **cannot**
know the table. Emit a write edge to the sentinel node `TBL:__UNKNOWN__` with
`confidence = "dynamic-unknown"`, capturing the raw statement text if available.
Also: any DML whose target does not resolve to a real object → `dynamic-unknown`.

### 4.5 Triggers — the invisible writes
A write to table `T` may fire a trigger that writes `U`; that `U` write is **not**
in the procedure's statements. Separate pass: for every trigger on a table that
appears as a write target, pull the trigger's own statements and emit
`trigger-induced` edges (source = the triggering table's write event).

### 4.6 Step ordinal
Within a unit: `LINE` (+`COL`) gives lexical order. Across units: order by the
call graph traversal from the chosen entry point. This is a **static ordinal**
(a superset ordering), distinct from `trace_order` (§5).

---

## 5. Runtime trace lane (optional overlay)

For the *true* order of one representative flow (e.g. place-order, settlement):
- **SQL trace** — `DBMS_MONITOR.SESSION_TRACE_ENABLE(binds=>TRUE, waits=>FALSE)`
  or event 10046 → the trace file lists executed SQL in real order.
- **Call tree** — `DBMS_HPROF` for the real procedure call tree.

Parse the trace → match statements back to IR edges by `SQL_ID` (PL/Scope gives
`SQL_ID` on `ALL_STATEMENTS`) → stamp `trace_order` and add `"trace"` to
`provenance`. Edges seen only at runtime (e.g. dynamic ones) get created as
`trace-only`. **A code change never regenerates the trace** — it stays stale
until the scenario is re-run; version it separately (§7).

---

## 6. The IR — JSON contract (extractor ⇄ renderer)

This is the single contract both sides code against. Freeze it first.

```jsonc
{
  "meta": {
    "schema_version": "1.0",
    "db": "NEWFO_DEV",
    "entry_point": "APP.PKG_ORDER.SUBMIT",     // optional: root of the walk
    "static_source":  { "generated_at": "2026-07-28T10:00:00Z",
                        "units": [ { "owner":"APP","name":"PKG_ORDER",
                                     "type":"PACKAGE BODY",
                                     "last_ddl_time":"2026-07-27T22:14:00Z" } ] },
    "trace_source":   { "present": true, "captured_at": "2026-07-26T09:00:00Z",
                        "scenario": "place_order_hose" }
  },

  "nodes": [
    { "id":"PROC:APP.PKG_ORDER.SUBMIT", "kind":"program_unit",
      "unit":"PKG_ORDER", "subprogram":"SUBMIT", "unit_type":"PACKAGE BODY" },
    { "id":"TBL:APP.ORDERS",       "kind":"table", "schema":"APP", "name":"ORDERS" },
    { "id":"TBL:__UNKNOWN__",      "kind":"unknown" }      // sentinel for dynamic targets
  ],

  "edges": [
    { "id":"e1", "type":"call",  "from":"PROC:APP.PKG_ORDER.SUBMIT",
      "to":"PROC:APP.PKG_VALIDATE.CHECK_ORDER",
      "step":1, "line":20, "confidence":"resolved",
      "reachability":"unconditional", "provenance":["static"] },

    { "id":"e2", "type":"write", "op":"INSERT",
      "from":"PROC:APP.PKG_ORDER.SUBMIT", "to":"TBL:APP.ORDERS",
      "step":2, "line":30, "sql_id":"8kyysdc8m75ag",
      "confidence":"resolved", "resolved_via":"direct",
      "reachability":"unconditional", "provenance":["static","trace"],
      "trace_order":5 },

    { "id":"e3", "type":"write", "op":"INSERT",
      "from":"PROC:APP.PKG_ORDER.SUBMIT", "to":"TBL:APP.ORDER_LOG",
      "step":5, "line":48, "confidence":"resolved", "resolved_via":"direct",
      "reachability":"branch-conditional", "guard":"IF p_market = 'HOSE'",
      "provenance":["static"] },

    { "id":"e4", "type":"write", "op":"INSERT",
      "from":"PROC:APP.PKG_DYNAMIC.LOG_DYNAMIC", "to":"TBL:__UNKNOWN__",
      "step":1, "line":11, "confidence":"dynamic-unknown",
      "raw_text":"INSERT INTO ' || v_tbl || ' ...", "provenance":["static"] },

    { "id":"e5", "type":"write", "op":"INSERT",
      "from":"TBL:APP.ORDERS", "to":"TBL:APP.ORDER_AUDIT",
      "confidence":"trigger-induced", "via_trigger":"TRG_ORDER_AUDIT",
      "provenance":["static"] }
  ]
}
```

### Enums
- `edge.type`: `write` | `call`
- `edge.op` (write only): `INSERT` | `UPDATE` | `DELETE` | `MERGE`
- `edge.confidence`: `resolved` | `dynamic-unknown` | `trigger-induced`
- `edge.resolved_via` (write only): `direct` | `synonym` | `view`
- `edge.reachability`: `unconditional` | `branch-conditional` | `loop`
- `edge.provenance[]`: `static` | `trace`

**Design rule:** the renderer must be able to draw the whole graph from this file
alone — no DB access. `sql_id` and `line` are the join keys back to the DB for
"jump to source".

---

## 7. Freshness / regeneration

Nothing auto-runs the extractor. Oracle refreshes the *dictionary* on recompile
(given §4.0); our pipeline must be triggered. Two mechanisms:

- **Pull (staleness check) — default.** IR stores each unit's `last_ddl_time`.
  On run, query `ALL_OBJECTS.LAST_DDL_TIME`; re-extract **only changed units**
  and splice into the IR (dictionary is per-object, so incremental is natural).
- **Push (optional).** A `AFTER CREATE OR ALTER ON SCHEMA` DDL trigger enqueues
  changed object names; or a post-deploy step in CI calls the extractor for the
  changeset.

Static and trace are versioned **independently** in `meta`. The renderer surfaces
`static: fresh · trace: stale (build N)` so no one mistakes an old order for the
current one.

---

## 8. Renderer — delegated to Claude Design

Claude Design owns the visual + interaction. It consumes **only** the IR (§6).
Give it this brief:

**Data in:** one IR JSON file (nodes + edges). No DB.

**Must do:**
- Layout the graph: node = table (and program unit), edge = write/call.
- **Step-by-step reveal**: a slider / play control that reveals edges in
  `step` order (static) — with a toggle to order by `trace_order` when a trace
  is present.
- Encode edge metadata visually: `op` (INSERT/UPDATE/DELETE/MERGE), and
  distinct treatments for `dynamic-unknown` (loud — this is the honesty signal),
  `trigger-induced` (dashed/derived), `branch-conditional` (show the `guard`).
- Show provenance (static-only vs confirmed-by-trace) and the freshness banner.
- "Jump to source" affordance using `line` / `sql_id` (can be a copy-to-clipboard
  of unit+line for now).

**Design-system constraints (the user's house style — keep strictly):**
- SVG, **`viewBox` width 680**.
- Color via **`c-{ramp}` classes**, **role-based** (source / processing / data /
  output / flag). Palette lives in one `<style>` block, swappable without touching
  layout.
- Role-color the confidence flags; `dynamic-unknown` gets the alert ramp.

Hand Claude Design: the IR schema above + a sample IR (generate one from the test
fixtures) + the delivered `design-architecture.svg` as the house-style reference.

---

## 9. Tech stack & project layout

- **Java 21**, plain **JDBC** (ojdbc11).
- **JGraphT** for the in-memory graph (cycle-safe traversal, topological where possible).
- **Jackson** for IR (de)serialization.
- CLI first; the renderer is a separate static-file consumer.

```
plsql-dataflow-tool/
  design.md                 ← this file
  test-fixtures.md          ← fixture catalog + expected extraction (test oracle)
  sql/
    01_schema.sql           ← tables, sequence, synonym
    02_packages.sql         ← test packages (all edge cases)
    03_triggers.sql         ← trigger fixture
    04_enable_plscope.sql   ← turn PL/Scope on + recompile
    05_extraction_queries.sql ← reference queries the Java tool ports to JDBC
  src/main/java/…           ← Claude Code implements:
    db/          DictionaryClient (runs the 05_* queries via JDBC)
    extract/     CallGraphExtractor, WriteExtractor, SynonymResolver,
                 TriggerExtractor, DynamicSqlFlagger
    trace/       TraceParser (10046), HprofParser        (phase 2)
    model/       Node, Edge, Ir  + Jackson mapping
    graph/       IrBuilder (JGraphT), StepOrdinal
    freshness/   StalenessChecker (LAST_DDL_TIME)
    Cli.java
```

---

## 10. Testing

`sql/01`–`04` build a schema whose packages deliberately exercise every
capability; `test-fixtures.md` is the **known-answer oracle**. The Java test
suite runs the extractor against this schema and asserts the produced IR matches
the expected edges (targets, ops, confidence, reachability). Because the fixtures
include the hard cases (dynamic SQL, synonym, trigger, cycle, branch), a green
suite means the *honesty* rules hold, not just the happy path.

---

## 11. Build order (suggested for Claude Code)

1. **Freeze the IR** (§6) as Java records + Jackson + a JSON schema.
2. `DictionaryClient` + port queries A/B from `sql/05` → call graph + writes.
3. `SynonymResolver`, then `TriggerExtractor`, then `DynamicSqlFlagger`.
4. `IrBuilder` (JGraphT) + `StepOrdinal`; emit IR; assert against `test-fixtures.md`.
5. `StalenessChecker` (incremental re-extract).
6. Generate a sample IR from the fixtures → hand to Claude Design for the renderer.
7. (Phase 2) trace overlay: `TraceParser` + `trace_order` merge.
