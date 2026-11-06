-- ============================================================
-- V6 — Audit module: append-only audit log
-- ============================================================

CREATE SCHEMA IF NOT EXISTS audit;

CREATE TABLE audit.audit_log
(
    id            UUID PRIMARY KEY,
    chama_id      UUID,
    actor_user_id UUID,
    action        VARCHAR(60) NOT NULL,
    target_type   VARCHAR(40) NOT NULL,
    target_id     VARCHAR(64),
    details       JSONB       NOT NULL DEFAULT '{}'::jsonb,
    ip_address    VARCHAR(45),
    occurred_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_audit_log_chama_time ON audit.audit_log (chama_id, occurred_at DESC);
CREATE INDEX idx_audit_log_actor ON audit.audit_log (actor_user_id, occurred_at DESC);

-- History must not be rewritten: reject UPDATE, DELETE and TRUNCATE for every role,
-- including the application's own database user.
CREATE FUNCTION audit.reject_modification() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'audit.audit_log is append-only (% rejected)', TG_OP;
END;
$$;

CREATE TRIGGER audit_log_no_update_delete
    BEFORE UPDATE OR DELETE ON audit.audit_log
    FOR EACH ROW EXECUTE FUNCTION audit.reject_modification();

CREATE TRIGGER audit_log_no_truncate
    BEFORE TRUNCATE ON audit.audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION audit.reject_modification();
