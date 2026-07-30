-- 04_enable_plscope.sql
-- PL/Scope collects data AT COMPILE TIME. Objects created in 02/03 before this
-- ran collected NOTHING. Recompile them WITH the setting active.

-- ---------------------------------------------------------------------------
-- OPTION A — recommended on the real analysis instance (needs ALTER SYSTEM).
-- Makes it the default so EVERY compile (incl. deploys / invalidation cascade)
-- self-collects and the data never silently disappears:
--
--   ALTER SYSTEM SET PLSCOPE_SETTINGS = 'IDENTIFIERS:ALL, STATEMENTS:ALL' SCOPE = BOTH;
--
-- Do this on DEV/TEST only (slower compile; data grows in SYSAUX).
-- ---------------------------------------------------------------------------

-- OPTION B — per-session, no ALTER SYSTEM privilege needed. Use for this test.
ALTER SESSION SET PLSCOPE_SETTINGS = 'IDENTIFIERS:ALL, STATEMENTS:ALL';

-- Recompile bodies + trigger so they collect under the active setting.
-- (No REUSE SETTINGS -> the compile picks up the session setting above.)
ALTER PACKAGE pkg_validate COMPILE BODY;
ALTER PACKAGE pkg_position COMPILE BODY;
ALTER PACKAGE pkg_dynamic  COMPILE BODY;
ALTER PACKAGE pkg_order    COMPILE BODY;
ALTER TRIGGER trg_order_audit COMPILE;

-- Sanity check: confirm the setting is actually stored per unit.
COLUMN name              FORMAT A16
COLUMN type              FORMAT A14
COLUMN plscope_settings  FORMAT A34
SELECT name, type, plscope_settings
  FROM user_plsql_object_settings
 WHERE name IN ('PKG_VALIDATE','PKG_POSITION','PKG_DYNAMIC','PKG_ORDER','TRG_ORDER_AUDIT')
 ORDER BY name, type;

-- Quick smoke: statement-type histogram for the fixtures.
SELECT type, COUNT(*) AS n
  FROM user_statements
 GROUP BY type
 ORDER BY n DESC;
