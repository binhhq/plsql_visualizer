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
