#!/usr/bin/env bash
# Captures an event-10046 trace of the fixture's place-order flow and copies it
# into src/test/resources/traces/ as a test fixture (design.md §5).
#
#   ./scripts/capture-trace.sh HOSE    # the branch-conditional write runs
#   ./scripts/capture-trace.sh HNX     # it does not — the divergence fixture
#
# Two scenarios exist on purpose. A trace that agrees with the static order
# proves the join works; a trace where a statically-present write never executes
# is the thing the tool exists to show, and only the second scenario has one.
set -euo pipefail

MARKET="${1:-HOSE}"
SVC=oracle
CONTAINER=plsql-oracle
PDB=FREEPDB1
SYS_PW=oracle
APP_USER=tscope_test
APP_PW=tscope
OUT_DIR="$(cd "$(dirname "$0")/.." && pwd)/src/test/resources/traces"
OUT_FILE="$OUT_DIR/place_order_$(echo "$MARKET" | tr '[:upper:]' '[:lower:]').trc"

mkdir -p "$OUT_DIR"

# DBMS_MONITOR is not granted to an ordinary schema by default, and the trace
# needs a session-level ALTER for the file identifier.
echo "==> Granting trace privileges to $APP_USER, flushing the shared pool"
docker exec -i "$CONTAINER" \
  sqlplus -s "sys/${SYS_PW}@localhost:1521/${PDB}" as sysdba >/dev/null <<SQL
GRANT EXECUTE ON dbms_monitor TO ${APP_USER};
GRANT ALTER SESSION TO ${APP_USER};
-- Capture cold, on purpose. With the packages already in the library cache a
-- trace of this flow is ~370 lines of nothing but the application's own SQL;
-- cold, Oracle re-reads the dictionary and the same flow becomes ~17 000 lines,
-- ~99% recursive lookups against SYS objects. That noise is the realistic case,
-- and a parser tested only against the warm trace is tested against a fantasy.
ALTER SYSTEM FLUSH SHARED_POOL;
EXIT
SQL

echo "==> Running the flow under trace (market=$MARKET)"
docker exec -i "$CONTAINER" \
  sqlplus -s "${APP_USER}/${APP_PW}@localhost:1521/${PDB}" > /tmp/capture-out.txt <<SQL
SET SERVEROUTPUT ON
SET FEEDBACK OFF
SET HEADING OFF
SET PAGESIZE 0
SET LINESIZE 300

-- The dynamic INSERT targets ORDER_LOG_<YYYYMM>, a name computed at runtime.
-- Without that table the flow dies at ORA-00942 before apply_fill and the trace
-- stops halfway — so create the current month's table first. It is deliberately
-- absent from 01_schema.sql: a table no static reference mentions is the whole
-- point of the dynamic-SQL fixture.
DECLARE
  v_tbl VARCHAR2(30) := 'ORDER_LOG_' || TO_CHAR(SYSDATE, 'YYYYMM');
BEGIN
  EXECUTE IMMEDIATE 'CREATE TABLE ' || v_tbl || ' (order_id NUMBER, note VARCHAR2(200))';
EXCEPTION WHEN OTHERS THEN
  IF SQLCODE <> -955 THEN RAISE; END IF;   -- -955 = already exists
END;
/

ALTER SESSION SET tracefile_identifier = 'PLACE_ORDER_${MARKET}';

BEGIN
  DBMS_MONITOR.SESSION_TRACE_ENABLE(binds => TRUE, waits => FALSE);
END;
/

BEGIN
  pkg_order.submit(p_customer_id => 1001, p_symbol => 'VIC', p_side => 'BUY',
                   p_qty => 100, p_price => 10, p_market => '${MARKET}');
  COMMIT;
END;
/

BEGIN
  DBMS_MONITOR.SESSION_TRACE_DISABLE;
END;
/

-- Must be read inside the traced session: this is the file it just wrote.
SELECT 'TRACEFILE=' || value FROM v\$diag_info WHERE name = 'Default Trace File';
EXIT
SQL

# Anchored and required to look like a path: the column header for the SELECT is
# itself "'TRACEFILE='||VALUE", which an unanchored match happily returns.
TRACE_PATH=$(grep -oE '^TRACEFILE=/[^ ]+\.trc' /tmp/capture-out.txt | head -1 | cut -d= -f2-)
if [ -z "$TRACE_PATH" ]; then
  echo "!! Could not determine the trace file. sqlplus said:" >&2
  cat /tmp/capture-out.txt >&2
  exit 1
fi

echo "==> Copying $TRACE_PATH"
docker cp "$CONTAINER:$TRACE_PATH" "$OUT_FILE"

echo ""
echo "Wrote $OUT_FILE ($(wc -l < "$OUT_FILE") lines, $(du -h "$OUT_FILE" | cut -f1))"
echo "Overlay it with:"
echo "  ./mvnw -q spring-boot:run -Dspring-boot.run.arguments=\"\\"
echo "    --plsql.task=trace --plsql.ir=target/ir.json \\"
echo "    --plsql.trace-file=$OUT_FILE --plsql.scenario=place_order_${MARKET}\""
