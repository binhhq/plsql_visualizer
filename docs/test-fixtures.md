# Test Fixtures — Known Answers

The `sql/` schema is built so each procedure exercises one tool capability with a
**known expected result**. The Java test suite runs the extractor against this
schema and asserts the produced IR matches the edges below. Because the hard
cases are included, a green suite means the **honesty rules** hold — not just the
happy path.

Build order: `01_schema` → `02_packages` → `03_triggers` → `04_enable_plscope`,
then run the extractor (or `05_extraction_queries` to check by hand).

## Capability coverage

| # | Fixture (unit) | Exercises | Expected signal |
|---|---|---|---|
| 1 | `PKG_VALIDATE.CHECK_ORDER` | plain write + a read that must NOT become an edge | 1 write edge; the `SELECT cash_balance` produces **no** edge |
| 2 | `PKG_ORDER.SUBMIT` INSERT ORDERS | resolved-static write | `confidence=resolved, resolved_via=direct` |
| 3 | `PKG_ORDER.SUBMIT` UPDATE via `ORD` | **synonym resolution** | target resolves `ORD → ORDERS`, `resolved_via=synonym` |
| 4 | `PKG_ORDER.SUBMIT` UPDATE CASH_BALANCE | resolved-static write | plain edge |
| 5 | `PKG_ORDER.SUBMIT` INSERT ORDER_LOG in `IF` | **branch-conditional** | `reachability=branch-conditional`, `guard` captured |
| 6 | `PKG_ORDER.SUBMIT` calls | **cross-package call graph** | 3 call edges out of SUBMIT |
| 7 | `PKG_DYNAMIC.LOG_DYNAMIC` EXECUTE IMMEDIATE | **dynamic SQL** | edge to `TBL:__UNKNOWN__`, `confidence=dynamic-unknown` — target NOT guessed |
| 8 | `PKG_POSITION.APPLY_FILL` MERGE | **MERGE op** | one edge `op=MERGE` to POSITIONS (not split into insert+update) |
| 9 | `PKG_POSITION.APPLY_FILL` UPDATE in `FOR` | **loop reachability** | `reachability=loop` |
| 10 | `PKG_POSITION.APPLY_FILL → PKG_ORDER.MARK_FILLED` | **cycle** | graph has cycle `PKG_ORDER ↔ PKG_POSITION`; traversal must not loop forever |
| 11 | `TRG_ORDER_AUDIT` | **trigger-induced write** | edge `ORDERS --INSERT--> ORDER_AUDIT`, `confidence=trigger-induced` — absent from SUBMIT's own statements |

## Expected edges (the assertion set)

**Call edges**
- `PKG_ORDER.SUBMIT` → `PKG_VALIDATE.CHECK_ORDER`
- `PKG_ORDER.SUBMIT` → `PKG_DYNAMIC.LOG_DYNAMIC`
- `PKG_ORDER.SUBMIT` → `PKG_POSITION.APPLY_FILL`
- `PKG_POSITION.APPLY_FILL` → `PKG_ORDER.MARK_FILLED`  *(closes the cycle)*

**Write edges**
| from (unit) | op | to (table) | confidence | resolved_via | reachability |
|---|---|---|---|---|---|
| `PKG_VALIDATE.CHECK_ORDER` | UPDATE | `VALIDATE_STATS` | resolved | direct | unconditional |
| `PKG_ORDER.SUBMIT` | INSERT | `ORDERS` | resolved | direct | unconditional |
| `PKG_ORDER.SUBMIT` | UPDATE | `ORDERS` | resolved | **synonym** | unconditional |
| `PKG_ORDER.SUBMIT` | UPDATE | `CASH_BALANCE` | resolved | direct | unconditional |
| `PKG_ORDER.SUBMIT` | INSERT | `ORDER_LOG` | resolved | direct | **branch-conditional** |
| `PKG_DYNAMIC.LOG_DYNAMIC` | INSERT | `__UNKNOWN__` | **dynamic-unknown** | — | unconditional |
| `PKG_POSITION.APPLY_FILL` | MERGE | `POSITIONS` | resolved | direct | unconditional |
| `PKG_POSITION.APPLY_FILL` | UPDATE | `POSITIONS` | resolved | direct | **loop** |
| `PKG_ORDER.MARK_FILLED` | UPDATE | `ORDERS` | resolved | direct | unconditional |

**Trigger-induced edge**
| from (table event) | op | to (table) | confidence | via_trigger |
|---|---|---|---|---|
| `ORDERS` (on INSERT) | INSERT | `ORDER_AUDIT` | trigger-induced | `TRG_ORDER_AUDIT` |

## Negative assertions (must NOT appear)
- No write edge for the `SELECT ... FROM cash_balance` in `CHECK_ORDER`.
- `PKG_DYNAMIC.LOG_DYNAMIC` must **not** produce an edge to `ORDER_LOG_YYYYMM`
  or any concrete table — the dynamic target stays `__UNKNOWN__`.
- `DUAL` (from the MERGE `USING`) must **not** appear as a write target.

## Notes on true order (phase 2)
Static `step` order for SUBMIT is the lexical/call sequence:
`CHECK_ORDER · INSERT ORDERS · UPDATE ORD · UPDATE CASH_BALANCE · [ORDER_LOG] · LOG_DYNAMIC · APPLY_FILL`.
The `ORDER_LOG` insert is only reached when `p_market='HOSE'` — static shows it in
sequence (superset); a runtime trace of a non-HOSE order would omit it. That gap is
exactly what the trace overlay makes visible.
