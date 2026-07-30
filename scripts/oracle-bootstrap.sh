#!/usr/bin/env bash
# Bootstrap the Oracle fixture schema for the PL/SQL Data-Flow Visualizer.
#
# What it does:
#   1. Starts the Oracle 23ai Free container (if not already up).
#   2. Waits until the DB is healthy.
#   3. Sets PLSCOPE_SETTINGS as the PDB default (so every compile self-collects).
#   4. Runs the fixtures 01 -> 04 as the tscope_test schema.
#   5. Prints a smoke check (PL/Scope settings + statement histogram).
#
# Re-runnable: the fixtures guard their DROPs, so you can run this repeatedly.
set -euo pipefail

cd "$(dirname "$0")/.."

SVC=oracle
PDB=FREEPDB1
SYS_PW=oracle
APP_USER=tscope_test
APP_PW=tscope

echo "==> Starting Oracle container..."
docker compose up -d "$SVC"

echo "==> Waiting for the database to become healthy (first boot can take a few minutes)..."
for i in $(seq 1 60); do
  status=$(docker inspect --format '{{.State.Health.Status}}' plsql-oracle 2>/dev/null || echo "starting")
  if [ "$status" = "healthy" ]; then
    echo "    healthy."
    break
  fi
  echo "    [$i/60] status=$status ..."
  sleep 10
  if [ "$i" -eq 60 ]; then
    echo "!! Timed out waiting for Oracle to become healthy." >&2
    echo "   Check logs: docker compose logs $SVC" >&2
    exit 1
  fi
done

# Helper: run a SQL file inside the container as the app schema.
run_app() {
  local file="$1"
  echo "==> Running $file  (as $APP_USER)"
  docker compose exec -T "$SVC" \
    sqlplus -S -L "${APP_USER}/${APP_PW}@localhost:1521/${PDB}" "@$file"
}

echo "==> Setting PLSCOPE_SETTINGS as the PDB default (belt-and-suspenders; 04 also sets it per session)..."
docker compose exec -T "$SVC" \
  sqlplus -S -L "sys/${SYS_PW}@localhost:1521/${PDB}" as sysdba <<'SQL' || \
  echo "   (ALTER SYSTEM skipped/failed — the per-session setting in 04 still collects PL/Scope.)"
WHENEVER SQLERROR EXIT SQL.SQLCODE
ALTER SYSTEM SET PLSCOPE_SETTINGS = 'IDENTIFIERS:ALL, STATEMENTS:ALL' SCOPE = BOTH;
EXIT
SQL

run_app /docs/01_schema.sql
run_app /docs/02_packages.sql
run_app /docs/03_triggers.sql
run_app /docs/04_enable_plscope.sql

echo ""
echo "============================================================"
echo " Oracle is ready."
echo "   JDBC : jdbc:oracle:thin:@//localhost:1521/${PDB}"
echo "   user : ${APP_USER}   password: ${APP_PW}"
echo "============================================================"