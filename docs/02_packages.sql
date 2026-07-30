-- 02_packages.sql
-- Test packages. Each writes/calls in a way that exercises one tool capability.
-- NOTE: all SPECS are created first, then all BODIES — because the bodies
-- reference each other across packages (PKG_ORDER <-> PKG_POSITION is a cycle).

-- =========================== SPECS ======================================
CREATE OR REPLACE PACKAGE pkg_validate AS
  PROCEDURE check_order(p_customer_id IN NUMBER, p_qty IN NUMBER);
END pkg_validate;
/
CREATE OR REPLACE PACKAGE pkg_position AS
  PROCEDURE apply_fill(p_customer_id IN NUMBER, p_symbol IN VARCHAR2,
                       p_qty IN NUMBER, p_order_id IN NUMBER);
END pkg_position;
/
CREATE OR REPLACE PACKAGE pkg_dynamic AS
  PROCEDURE log_dynamic(p_order_id IN NUMBER);
END pkg_dynamic;
/
CREATE OR REPLACE PACKAGE pkg_order AS
  PROCEDURE submit(p_customer_id IN NUMBER, p_symbol IN VARCHAR2, p_side IN VARCHAR2,
                   p_qty IN NUMBER, p_price IN NUMBER, p_market IN VARCHAR2);
  PROCEDURE mark_filled(p_order_id IN NUMBER);
END pkg_order;
/

-- =========================== BODIES =====================================

-- PKG_VALIDATE: looks read-only (a SELECT) but has ONE resolved-static UPDATE.
CREATE OR REPLACE PACKAGE BODY pkg_validate AS
  PROCEDURE check_order(p_customer_id IN NUMBER, p_qty IN NUMBER) IS
    v_avail NUMBER;
  BEGIN
    SELECT available INTO v_avail
      FROM cash_balance
     WHERE customer_id = p_customer_id;            -- SELECT (read, no edge)

    IF v_avail < p_qty THEN
      RAISE_APPLICATION_ERROR(-20001, 'insufficient funds');
    END IF;

    UPDATE validate_stats SET checks = checks + 1  -- WRITE: UPDATE VALIDATE_STATS
     WHERE id = 1;
  END check_order;
END pkg_validate;
/

-- PKG_POSITION: MERGE (upsert), a loop write, and a CALL BACK into PKG_ORDER (cycle).
CREATE OR REPLACE PACKAGE BODY pkg_position AS
  PROCEDURE apply_fill(p_customer_id IN NUMBER, p_symbol IN VARCHAR2,
                       p_qty IN NUMBER, p_order_id IN NUMBER) IS
  BEGIN
    MERGE INTO positions p                          -- WRITE: MERGE POSITIONS
    USING (SELECT p_customer_id AS customer_id, p_symbol AS symbol FROM dual) s
       ON (p.customer_id = s.customer_id AND p.symbol = s.symbol)
    WHEN MATCHED THEN
      UPDATE SET p.qty = p.qty + p_qty
    WHEN NOT MATCHED THEN
      INSERT (customer_id, symbol, qty) VALUES (p_customer_id, p_symbol, p_qty);

    FOR i IN 1 .. 1 LOOP
      UPDATE positions SET qty = qty                -- WRITE: UPDATE (reachability = loop)
       WHERE customer_id = p_customer_id AND symbol = p_symbol;
    END LOOP;

    pkg_order.mark_filled(p_order_id);              -- CALL back -> cycle with PKG_ORDER
  END apply_fill;
END pkg_position;
/

-- PKG_DYNAMIC: EXECUTE IMMEDIATE with a runtime-computed table name.
-- Static analysis MUST NOT resolve the target -> dynamic-unknown.
CREATE OR REPLACE PACKAGE BODY pkg_dynamic AS
  PROCEDURE log_dynamic(p_order_id IN NUMBER) IS
    v_tbl VARCHAR2(30);
    v_sql VARCHAR2(200);
  BEGIN
    v_tbl := 'ORDER_LOG_' || TO_CHAR(SYSDATE, 'YYYYMM');       -- target unknown at compile time
    v_sql := 'INSERT INTO ' || v_tbl || ' (order_id, note) VALUES (:1, :2)';
    EXECUTE IMMEDIATE v_sql USING p_order_id, 'dynamic';       -- WRITE: dynamic-unknown
  END log_dynamic;
END pkg_dynamic;
/

-- PKG_ORDER: the entry point. Cross-package calls, synonym write, branch write,
-- dynamic-SQL call, plus the resolved-static insert that fires a trigger.
CREATE OR REPLACE PACKAGE BODY pkg_order AS

  PROCEDURE submit(p_customer_id IN NUMBER, p_symbol IN VARCHAR2, p_side IN VARCHAR2,
                   p_qty IN NUMBER, p_price IN NUMBER, p_market IN VARCHAR2) IS
    v_order_id NUMBER;
  BEGIN
    pkg_validate.check_order(p_customer_id, p_qty);            -- CALL cross-package

    v_order_id := order_seq.NEXTVAL;

    INSERT INTO orders (order_id, customer_id, symbol, side, qty, price, status)
    VALUES (v_order_id, p_customer_id, p_symbol, p_side, p_qty, p_price, 'NEW');
    -- ^ WRITE: INSERT ORDERS (resolved). Fires TRG_ORDER_AUDIT -> ORDER_AUDIT (trigger-induced)

    UPDATE ord SET status = 'BLOCKED'                          -- WRITE via SYNONYM ord -> ORDERS
     WHERE order_id = v_order_id;

    UPDATE cash_balance                                        -- WRITE: UPDATE CASH_BALANCE
       SET available = available - (p_qty * p_price),
           blocked   = blocked   + (p_qty * p_price)
     WHERE customer_id = p_customer_id;

    IF p_market = 'HOSE' THEN
      INSERT INTO order_log (order_id, note)                   -- WRITE: branch-conditional
      VALUES (v_order_id, 'HOSE routed');
    END IF;

    pkg_dynamic.log_dynamic(v_order_id);                       -- CALL -> dynamic-unknown write

    pkg_position.apply_fill(p_customer_id, p_symbol, p_qty, v_order_id); -- CALL (cycle)
  END submit;

  PROCEDURE mark_filled(p_order_id IN NUMBER) IS
  BEGIN
    UPDATE orders SET status = 'FILLED'                        -- WRITE: UPDATE ORDERS
     WHERE order_id = p_order_id;
  END mark_filled;

END pkg_order;
/

-- Report any compile errors
SHOW ERRORS
