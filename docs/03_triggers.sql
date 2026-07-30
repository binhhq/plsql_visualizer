-- 03_triggers.sql
-- Trigger-induced write fixture.
-- An INSERT into ORDERS (from PKG_ORDER.SUBMIT) fires this trigger, which
-- writes ORDER_AUDIT. That write is INVISIBLE from the procedure's own
-- statements — the tool must find it via a separate trigger pass and emit a
-- `trigger-induced` edge (ORDERS --INSERT--> ORDER_AUDIT via TRG_ORDER_AUDIT).

CREATE OR REPLACE TRIGGER trg_order_audit
AFTER INSERT ON orders
FOR EACH ROW
BEGIN
  INSERT INTO order_audit (audit_id, order_id, action, ts)
  VALUES (audit_seq.NEXTVAL, :NEW.order_id, 'INSERT', SYSTIMESTAMP);
END;
/

SHOW ERRORS
