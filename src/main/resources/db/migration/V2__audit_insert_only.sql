-- Audit tables record decisions and events. Nobody, including someone with
-- direct SQL access, may rewrite or erase that history: every audit table
-- gets this function attached as a trigger that refuses UPDATE, DELETE and
-- TRUNCATE outright, naming the table in the error.

CREATE FUNCTION prevent_audit_modification() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'audit table % is insert-only', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_trade_order_no_update_delete
    BEFORE UPDATE OR DELETE ON trade_order
    FOR EACH ROW EXECUTE FUNCTION prevent_audit_modification();

CREATE TRIGGER trg_trade_order_no_truncate
    BEFORE TRUNCATE ON trade_order
    FOR EACH STATEMENT EXECUTE FUNCTION prevent_audit_modification();

CREATE TRIGGER trg_order_event_no_update_delete
    BEFORE UPDATE OR DELETE ON order_event
    FOR EACH ROW EXECUTE FUNCTION prevent_audit_modification();

CREATE TRIGGER trg_order_event_no_truncate
    BEFORE TRUNCATE ON order_event
    FOR EACH STATEMENT EXECUTE FUNCTION prevent_audit_modification();

CREATE TRIGGER trg_decision_no_update_delete
    BEFORE UPDATE OR DELETE ON decision
    FOR EACH ROW EXECUTE FUNCTION prevent_audit_modification();

CREATE TRIGGER trg_decision_no_truncate
    BEFORE TRUNCATE ON decision
    FOR EACH STATEMENT EXECUTE FUNCTION prevent_audit_modification();

CREATE TRIGGER trg_rule_result_no_update_delete
    BEFORE UPDATE OR DELETE ON rule_result
    FOR EACH ROW EXECUTE FUNCTION prevent_audit_modification();

CREATE TRIGGER trg_rule_result_no_truncate
    BEFORE TRUNCATE ON rule_result
    FOR EACH STATEMENT EXECUTE FUNCTION prevent_audit_modification();

CREATE TRIGGER trg_quarantine_no_update_delete
    BEFORE UPDATE OR DELETE ON quarantine
    FOR EACH ROW EXECUTE FUNCTION prevent_audit_modification();

CREATE TRIGGER trg_quarantine_no_truncate
    BEFORE TRUNCATE ON quarantine
    FOR EACH STATEMENT EXECUTE FUNCTION prevent_audit_modification();

CREATE TRIGGER trg_quarantine_resolution_no_update_delete
    BEFORE UPDATE OR DELETE ON quarantine_resolution
    FOR EACH ROW EXECUTE FUNCTION prevent_audit_modification();

CREATE TRIGGER trg_quarantine_resolution_no_truncate
    BEFORE TRUNCATE ON quarantine_resolution
    FOR EACH STATEMENT EXECUTE FUNCTION prevent_audit_modification();

CREATE TRIGGER trg_setting_value_no_update_delete
    BEFORE UPDATE OR DELETE ON setting_value
    FOR EACH ROW EXECUTE FUNCTION prevent_audit_modification();

CREATE TRIGGER trg_setting_value_no_truncate
    BEFORE TRUNCATE ON setting_value
    FOR EACH STATEMENT EXECUTE FUNCTION prevent_audit_modification();

CREATE TRIGGER trg_limit_change_request_no_update_delete
    BEFORE UPDATE OR DELETE ON limit_change_request
    FOR EACH ROW EXECUTE FUNCTION prevent_audit_modification();

CREATE TRIGGER trg_limit_change_request_no_truncate
    BEFORE TRUNCATE ON limit_change_request
    FOR EACH STATEMENT EXECUTE FUNCTION prevent_audit_modification();

CREATE TRIGGER trg_limit_change_preview_no_update_delete
    BEFORE UPDATE OR DELETE ON limit_change_preview
    FOR EACH ROW EXECUTE FUNCTION prevent_audit_modification();

CREATE TRIGGER trg_limit_change_preview_no_truncate
    BEFORE TRUNCATE ON limit_change_preview
    FOR EACH STATEMENT EXECUTE FUNCTION prevent_audit_modification();

CREATE TRIGGER trg_limit_change_approval_no_update_delete
    BEFORE UPDATE OR DELETE ON limit_change_approval
    FOR EACH ROW EXECUTE FUNCTION prevent_audit_modification();

CREATE TRIGGER trg_limit_change_approval_no_truncate
    BEFORE TRUNCATE ON limit_change_approval
    FOR EACH STATEMENT EXECUTE FUNCTION prevent_audit_modification();

CREATE TRIGGER trg_limit_change_activation_no_update_delete
    BEFORE UPDATE OR DELETE ON limit_change_activation
    FOR EACH ROW EXECUTE FUNCTION prevent_audit_modification();

CREATE TRIGGER trg_limit_change_activation_no_truncate
    BEFORE TRUNCATE ON limit_change_activation
    FOR EACH STATEMENT EXECUTE FUNCTION prevent_audit_modification();

CREATE TRIGGER trg_limit_change_cancellation_no_update_delete
    BEFORE UPDATE OR DELETE ON limit_change_cancellation
    FOR EACH ROW EXECUTE FUNCTION prevent_audit_modification();

CREATE TRIGGER trg_limit_change_cancellation_no_truncate
    BEFORE TRUNCATE ON limit_change_cancellation
    FOR EACH STATEMENT EXECUTE FUNCTION prevent_audit_modification();

