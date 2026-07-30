-- 05_extraction_queries.sql
-- Reference queries. The Java extractor ports these to JDBC (one per extractor
-- class). Run them after 04 to eyeball that PL/Scope captured the fixtures.
-- Key facts used here:
--   * ALL_STATEMENTS has TYPE (INSERT/UPDATE/DELETE/MERGE/...), LINE, SQL_ID,
--     USAGE_ID, USAGE_CONTEXT_ID.
--   * ALL_IDENTIFIERS.USAGE_CONTEXT_ID is a reflexive FK to USAGE_ID, so a table
--     referenced inside a statement chains up to that statement's USAGE_ID.
--   * SIGNATURE is globally unique -> use it to resolve a CALL to its owner.

-- ===========================================================================
-- A. WRITES  (proc/trigger -> target object, with op + line)
--    Immediate child of the DML statement = the target of INSERT/UPDATE/DELETE.
--    (Source tables in an INSERT..SELECT are nested deeper and are excluded here.)
-- ===========================================================================
SELECT s.object_name                AS unit,
       s.object_type                AS unit_type,
       s.type                       AS op,          -- INSERT / UPDATE / DELETE / MERGE
       s.line,
       s.sql_id,
       i.name                       AS target_object,
       i.type                       AS target_kind  -- TABLE / VIEW / SYNONYM
  FROM user_statements s
  JOIN user_identifiers i
    ON  i.object_name       = s.object_name
    AND i.object_type       = s.object_type
    AND i.usage_context_id  = s.usage_id           -- table sits directly under the statement
    AND i.usage             = 'REFERENCE'
    AND i.type IN ('TABLE','VIEW','SYNONYM')
 WHERE s.type IN ('INSERT','UPDATE','DELETE','MERGE')
 ORDER BY s.object_name, s.line;

-- ===========================================================================
-- B. CALL GRAPH  (caller unit -> callee, resolved to the callee's OWNER unit)
--    CALL usage gives the callee; join by SIGNATURE to its DECLARATION to learn
--    which package it lives in (this is the cross-package resolution).
-- ===========================================================================
SELECT c.object_name   AS caller_unit,
       c.object_type   AS caller_type,
       c.line,
       d.object_name   AS callee_unit,
       c.name          AS callee_subprogram
  FROM user_identifiers c
  JOIN user_identifiers d
    ON  d.signature = c.signature
    AND d.usage IN ('DECLARATION','DEFINITION')
 WHERE c.usage = 'CALL'
   AND d.object_type IN ('PACKAGE','PACKAGE BODY','PROCEDURE','FUNCTION')
 ORDER BY c.object_name, c.line;

-- ===========================================================================
-- C. SYNONYM RESOLUTION  (target_kind = SYNONYM  ->  base table)
--    Join query A's target (when kind = SYNONYM) to this.
-- ===========================================================================
SELECT synonym_name, table_owner, table_name
  FROM all_synonyms
 WHERE owner IN (USER, 'PUBLIC')
   AND synonym_name IN (SELECT name FROM user_identifiers WHERE type = 'SYNONYM');

-- ===========================================================================
-- D. DYNAMIC SQL  (targets invisible to static -> confidence = dynamic-unknown)
--    Verify the exact TYPE label on your instance first:
--      SELECT DISTINCT type FROM user_statements ORDER BY 1;
--    On 19c dynamic execution shows as EXECUTE IMMEDIATE. The tool should ALSO
--    treat any DML whose target didn't resolve in A as dynamic-unknown.
-- ===========================================================================
SELECT object_name AS unit, object_type, line, type
  FROM user_statements
 WHERE type LIKE 'EXECUTE%'
 ORDER BY object_name, line;

-- ===========================================================================
-- E. TRIGGER-INDUCED WRITES  (write to on_table fires trigger that writes target)
--    Emit edges: on_table --op--> target  (via_trigger = trigger_name).
-- ===========================================================================
SELECT t.trigger_name,
       t.table_name        AS on_table,
       s.type              AS op,
       s.line,
       i.name              AS target_object,
       i.type              AS target_kind
  FROM user_triggers t
  JOIN user_statements s
    ON  s.object_name = t.trigger_name
    AND s.object_type = 'TRIGGER'
    AND s.type IN ('INSERT','UPDATE','DELETE','MERGE')
  JOIN user_identifiers i
    ON  i.object_name      = t.trigger_name
    AND i.object_type      = 'TRIGGER'
    AND i.usage_context_id = s.usage_id
    AND i.usage            = 'REFERENCE'
    AND i.type IN ('TABLE','VIEW','SYNONYM')
 ORDER BY t.trigger_name, s.line;

-- ===========================================================================
-- F. FRESHNESS  (staleness pull — re-extract only changed units)
-- ===========================================================================
SELECT object_name, object_type, last_ddl_time
  FROM user_objects
 WHERE object_type IN ('PACKAGE BODY','TRIGGER')
   AND object_name IN ('PKG_VALIDATE','PKG_POSITION','PKG_DYNAMIC','PKG_ORDER','TRG_ORDER_AUDIT')
 ORDER BY object_name;
