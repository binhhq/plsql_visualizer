# Sample IR — renderer handoff

`ir-fixtures.json` is a real IR, extracted by the tool from the fixture schema in
`docs/01_schema.sql` … `docs/04_enable_plscope.sql`. It is the artefact to hand
to Claude Design along with `docs/design.md` §6 (the contract) and §8 (the brief).

Regenerate it after any extractor change:

```bash
./scripts/oracle-bootstrap.sh          # once, to build the fixture schema
./mvnw -q exec:java \
  -Dexec.mainClass=com.example.plsqlvisualizer.Cli \
  -Dexec.args="--entry PKG_ORDER.SUBMIT --out samples/ir-fixtures.json"
```

## Why this sample and not a handwritten one

The fixtures were built so every hard case appears exactly once, which makes this
file a complete exercise of the visual language the renderer has to speak. Each
edge case below is present in the sample, so a renderer that draws this file
correctly has nothing left to special-case:

| In the sample | Edge | What the renderer must show |
|---|---|---|
| `dynamic-unknown` | `LOG_DYNAMIC → TBL:__UNKNOWN__` | The loud one. A write happened and we cannot say where — the alert ramp. |
| `trigger-induced` | `ORDERS → ORDER_AUDIT` | A write nobody wrote. Derived treatment (dashed), labelled with `via_trigger`. |
| `branch-conditional` | `SUBMIT → ORDER_LOG` | Carries `guard`: `IF p_market = 'HOSE'`. May not run at all. |
| `loop` | `APPLY_FILL → POSITIONS` | Runs an unknown number of times. |
| `resolved_via: synonym` | `SUBMIT → ORDERS` | The code says `ORD`; the graph says `ORDERS`. |
| `MERGE` | `APPLY_FILL → POSITIONS` | One op, not an INSERT and an UPDATE. |
| call cycle | `APPLY_FILL → MARK_FILLED` | `PKG_ORDER ⇄ PKG_POSITION`. Layout must not assume a DAG. |

## Notes for layout

- 14 edges, `step` 1–14, contiguous and unique — the step slider can index them directly.
- `trace_source.present` is `false`, so the freshness banner should read
  `static: fresh · trace: none`. There is no `trace_order` on any edge yet.
- The trigger edge runs **table → table**; every other edge starts at a
  `program_unit`. A layout that assumes edges always leave a procedure will
  misplace it.
- `TBL:__UNKNOWN__` is a single shared sentinel node. Every unresolved write in
  the schema converges on it, so its in-degree grows with real code — it should
  not be laid out as an ordinary leaf.

## The traced sample

`ir-fixtures-traced.json` is the same IR with a real 10046 trace of the
place-order flow overlaid (`--trace`, design.md §5). It is the file to develop
trace-mode UI against, because it contains every case that mode has to handle:

- 10 edges carrying `provenance: ["static","trace"]` with a dense `trace_order`;
- the trigger-induced write ordered **after** the INSERT that fired it — the trace
  file records it before, and untangling that is the parser's job, not the
  renderer's;
- one `trace-resolved` edge, `PKG_DYNAMIC.LOG_DYNAMIC → ORDER_LOG_202607`, with
  `resolves` pointing at the `dynamic-unknown` edge it explains. Both edges exist:
  static still cannot name that target, and one run is not proof of what the code
  does;
- `call` edges with no `trace_order` at all, because 10046 records SQL and not
  PL/SQL control flow.

For the "a write that never ran" case, overlay the other scenario instead —
`src/test/resources/traces/place_order_hnx.trc` takes the other branch, so the
guarded write stays `provenance: ["static"]` with no `trace_order` and
`trace_source.not_executed` is 1.

**Known gap:** the renderer's `trace_order` toggle is markup only — the button
enables itself when a trace is present, but no handler is bound, so the graph
cannot actually be re-ordered yet. Everything else in the renderer draws a traced
IR unchanged (verified: 13 nodes, 15 edges, the new table and edge included).

## The renderer

`renderer.html` is the delivered renderer — one self-contained file, no build, no
network. Open it directly. It embeds its IR in `<script id="ir-data">`, so the
page and the data it draws travel together; to point it at a different IR, swap
the contents of that block.

Beyond the §8 brief (step-through reveal, confidence treatments, freshness
banner) it carries a **package filter**. Type a package name — substring,
case-insensitive — and the graph narrows to that package's subgraph:

- every write and outbound call from the package's own subprograms;
- **inbound** calls, so "who calls this?" is answerable without clearing the filter;
- trigger-induced writes fired by tables the package writes. These are
  consequences of its own DML; hiding them would rebuild exactly the blind spot
  the tool exists to remove.

The step slider re-indexes to the scoped edges, so stepping never walks off the
end of what is drawn. A query matching no package shows an empty state naming
the packages that do exist, rather than an empty graph.

## Tests

The renderer has no framework and no dependencies; the tests run on plain `node`
and both exit non-zero on failure.

```bash
node samples/tests/scope-test.js     # the scope algorithm, against the real IR
node samples/tests/render-test.js    # the whole renderer, against a DOM stub
```

Both read their subject out of `renderer.html` itself — the real `computeScope`
source and the real IIFE, not a copy — so they cannot pass against code that has
since moved on. If a test dies with *"the anchors moved"*, a refactor renamed the
landmark it slices on; re-point the `indexOf` anchors in the test, do not
reintroduce a copy of the logic.

`scope-test` asserts the scope contract above, not just edge counts: counts alone
would still pass if the wrong edges were picked.
