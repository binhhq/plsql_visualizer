-- 01_schema.sql
-- Test schema for the PL/SQL Data-Flow Visualizer.
-- Run as a dedicated test user (e.g. TSCOPE_TEST) on a DEV/TEST instance.
-- Re-runnable: drops are guarded so you can re-run from scratch.

SET SERVEROUTPUT ON

DECLARE
  PROCEDURE drop_ignore(p_ddl IN VARCHAR2) IS
  BEGIN
    EXECUTE IMMEDIATE p_ddl;
  EXCEPTION WHEN OTHERS THEN
    IF SQLCODE NOT IN (-942, -2289, -1434, -4080) THEN RAISE; END IF; -- object/seq/trigger not found
  END;
BEGIN
  drop_ignore('DROP SYNONYM ord');
  drop_ignore('DROP SEQUENCE order_seq');
  drop_ignore('DROP SEQUENCE audit_seq');
  drop_ignore('DROP TABLE orders          PURGE');
  drop_ignore('DROP TABLE positions       PURGE');
  drop_ignore('DROP TABLE cash_balance    PURGE');
  drop_ignore('DROP TABLE order_log       PURGE');
  drop_ignore('DROP TABLE order_audit     PURGE');
  drop_ignore('DROP TABLE validate_stats  PURGE');
END;
/

-- Core tables -------------------------------------------------------------
CREATE TABLE orders (
  order_id     NUMBER        PRIMARY KEY,
  customer_id  NUMBER        NOT NULL,
  symbol       VARCHAR2(12)  NOT NULL,
  side         VARCHAR2(4),
  qty          NUMBER,
  price        NUMBER,
  status       VARCHAR2(16)
);

CREATE TABLE positions (
  customer_id  NUMBER        NOT NULL,
  symbol       VARCHAR2(12)  NOT NULL,
  qty          NUMBER        DEFAULT 0,
  CONSTRAINT pk_positions PRIMARY KEY (customer_id, symbol)
);

CREATE TABLE cash_balance (
  customer_id  NUMBER        PRIMARY KEY,
  available    NUMBER        DEFAULT 0,
  blocked      NUMBER        DEFAULT 0
);

CREATE TABLE order_log (
  order_id     NUMBER,
  note         VARCHAR2(200)
);

-- Written ONLY by a trigger (trigger-induced write fixture) ---------------
CREATE TABLE order_audit (
  audit_id     NUMBER        PRIMARY KEY,
  order_id     NUMBER,
  action       VARCHAR2(16),
  ts           TIMESTAMP
);

-- Written by a read-only-looking validate proc (single UPDATE fixture) ----
CREATE TABLE validate_stats (
  id           NUMBER        PRIMARY KEY,
  checks       NUMBER        DEFAULT 0
);
INSERT INTO validate_stats (id, checks) VALUES (1, 0);

-- Sequences ---------------------------------------------------------------
CREATE SEQUENCE order_seq START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE audit_seq START WITH 1 INCREMENT BY 1;

-- Synonym (synonym-resolution fixture): ORD -> ORDERS ---------------------
CREATE SYNONYM ord FOR orders;

-- Seed a customer so runtime execution (optional) works
INSERT INTO cash_balance (customer_id, available, blocked) VALUES (1001, 1000000, 0);
COMMIT;
