-- Phase 4: contribution periods, what each member owes per period, and recorded payments.
--
-- * periods: one per contribution cycle (a month "2026-11" or an ISO week "2026-W45"), opened
--   automatically up to the current one. The amount due is fixed when the period opens.
-- * dues: what one member owes for one period. Seated members owe every period whose due date
--   is on or after the day they joined.
-- * payments: money received from a member, each posted to the ledger. Corrected by reversal
--   (status REVERSED), never deleted.
-- Payments are applied to the oldest dues first, so arrears are computed, not stored.

CREATE SCHEMA IF NOT EXISTS contribution;

CREATE TABLE contribution.periods
(
    id               UUID PRIMARY KEY,
    tenant_id        UUID        NOT NULL DEFAULT public.app_current_tenant(),
    period_key       VARCHAR(10) NOT NULL,
    frequency        VARCHAR(10) NOT NULL CHECK (frequency IN ('MONTHLY', 'WEEKLY')),
    start_date       DATE        NOT NULL,
    end_date         DATE        NOT NULL,
    due_date         DATE        NOT NULL,
    amount_due_minor BIGINT      NOT NULL CHECK (amount_due_minor > 0),
    currency         VARCHAR(3)  NOT NULL,
    opened_at        TIMESTAMPTZ NOT NULL,
    CHECK (start_date <= due_date AND due_date <= end_date),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, period_key)
);
CREATE INDEX idx_periods_tenant_due ON contribution.periods (tenant_id, due_date);

CREATE TABLE contribution.dues
(
    id             UUID PRIMARY KEY,
    tenant_id      UUID        NOT NULL DEFAULT public.app_current_tenant(),
    period_id      UUID        NOT NULL,
    member_user_id UUID        NOT NULL,
    amount_minor   BIGINT      NOT NULL CHECK (amount_minor > 0),
    created_at     TIMESTAMPTZ NOT NULL,
    UNIQUE (period_id, member_user_id),
    FOREIGN KEY (tenant_id, period_id) REFERENCES contribution.periods (tenant_id, id)
);
CREATE INDEX idx_dues_tenant_member ON contribution.dues (tenant_id, member_user_id);

CREATE TABLE contribution.payments
(
    id                UUID PRIMARY KEY,
    tenant_id         UUID         NOT NULL DEFAULT public.app_current_tenant(),
    member_user_id    UUID         NOT NULL,
    amount_minor      BIGINT       NOT NULL CHECK (amount_minor > 0),
    currency          VARCHAR(3)   NOT NULL,
    method            VARCHAR(10)  NOT NULL CHECK (method IN ('CASH', 'BANK', 'MPESA')),
    reference         VARCHAR(100),
    paid_on           DATE         NOT NULL,
    notes             VARCHAR(300),
    status            VARCHAR(10)  NOT NULL CHECK (status IN ('RECORDED', 'REVERSED')),
    ledger_entry_id   UUID         NOT NULL,
    recorded_by       UUID         NOT NULL,
    recorded_at       TIMESTAMPTZ  NOT NULL,
    reversed_by       UUID,
    reversed_at       TIMESTAMPTZ,
    reversal_reason   VARCHAR(300),
    reversal_entry_id UUID,
    version           BIGINT       NOT NULL DEFAULT 0,
    CHECK ((status = 'REVERSED') = (reversed_at IS NOT NULL))
);
-- One M-Pesa code or bank slip is one payment: recording it twice is refused.
CREATE UNIQUE INDEX ux_payments_reference ON contribution.payments (tenant_id, method, upper(reference))
    WHERE reference IS NOT NULL AND status = 'RECORDED';
CREATE INDEX idx_payments_tenant_member ON contribution.payments (tenant_id, member_user_id, paid_on);
CREATE INDEX idx_payments_tenant_paid_on ON contribution.payments (tenant_id, paid_on DESC);

-- ---- Privileges and row-level security --------------------------------------------------------

GRANT USAGE ON SCHEMA contribution TO chama_app;
GRANT SELECT, INSERT ON contribution.periods, contribution.dues TO chama_app;
-- Payments change status once (reversal); they are never deleted.
GRANT SELECT, INSERT, UPDATE ON contribution.payments TO chama_app;

ALTER TABLE contribution.periods ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON contribution.periods
    USING (public.app_rls_bypass() OR tenant_id = public.app_current_tenant())
    WITH CHECK (public.app_rls_bypass() OR tenant_id = public.app_current_tenant());

ALTER TABLE contribution.dues ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON contribution.dues
    USING (public.app_rls_bypass() OR tenant_id = public.app_current_tenant())
    WITH CHECK (public.app_rls_bypass() OR tenant_id = public.app_current_tenant());

ALTER TABLE contribution.payments ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON contribution.payments
    USING (public.app_rls_bypass() OR tenant_id = public.app_current_tenant())
    WITH CHECK (public.app_rls_bypass() OR tenant_id = public.app_current_tenant());
