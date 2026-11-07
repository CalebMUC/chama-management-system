-- Phase 7: expenses, with maker-checker approval. An official records an expense; a quorum of
-- other officials approves it; then it is paid, which posts to the ledger (Dr the expense
-- account, Cr cash/bank/M-Pesa).

-- The secretary now approves expenses too (DefaultRolePermissions), so the default quorum of two
-- approvers (chairperson + secretary) can be met in every existing chama.
INSERT INTO member.role_permissions (tenant_id, role, permission)
SELECT c.id, 'SECRETARY', 'EXPENSE_APPROVE'
  FROM chama.chamas c
ON CONFLICT DO NOTHING;

CREATE SCHEMA IF NOT EXISTS expense;

CREATE TABLE expense.expenses
(
    id                 UUID PRIMARY KEY,
    tenant_id          UUID         NOT NULL DEFAULT public.app_current_tenant(),
    amount_minor       BIGINT       NOT NULL CHECK (amount_minor > 0),
    currency           VARCHAR(3)   NOT NULL,
    payee              VARCHAR(120) NOT NULL,
    description        VARCHAR(300) NOT NULL,
    -- The expense account charged, by code (e.g. 5000 Operating expenses).
    account_code       VARCHAR(10)  NOT NULL,
    incurred_on        DATE         NOT NULL,
    requested_by       UUID         NOT NULL,
    required_approvals INT          NOT NULL CHECK (required_approvals >= 1),
    status             VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'PAID', 'REJECTED', 'CANCELLED')),
    rejected_by        UUID,
    rejected_at        TIMESTAMPTZ,
    rejection_reason   VARCHAR(300),
    cancelled_at       TIMESTAMPTZ,
    paid_on            DATE,
    paid_by            UUID,
    payment_method     VARCHAR(10) CHECK (payment_method IN ('CASH', 'BANK', 'MPESA')),
    payment_reference  VARCHAR(100),
    payment_entry_id   UUID,
    created_at         TIMESTAMPTZ  NOT NULL,
    version            BIGINT       NOT NULL DEFAULT 0,
    CHECK (status <> 'PAID' OR payment_entry_id IS NOT NULL),
    UNIQUE (tenant_id, id)
);
CREATE INDEX idx_expenses_tenant_status ON expense.expenses (tenant_id, status, incurred_on DESC);
CREATE INDEX idx_expenses_tenant_paid ON expense.expenses (tenant_id, paid_on) WHERE status = 'PAID';
CREATE UNIQUE INDEX ux_expenses_payment_reference ON expense.expenses (tenant_id, payment_method, upper(payment_reference))
    WHERE payment_reference IS NOT NULL;

-- One row per official's approval; never changed.
CREATE TABLE expense.approvals
(
    tenant_id        UUID        NOT NULL DEFAULT public.app_current_tenant(),
    expense_id       UUID        NOT NULL,
    approver_user_id UUID        NOT NULL,
    note             VARCHAR(300),
    approved_at      TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, expense_id, approver_user_id),
    FOREIGN KEY (tenant_id, expense_id) REFERENCES expense.expenses (tenant_id, id)
);

GRANT USAGE ON SCHEMA expense TO chama_app;
-- Expenses change as they are approved and paid; nothing is ever deleted.
GRANT SELECT, INSERT, UPDATE ON expense.expenses TO chama_app;
GRANT SELECT, INSERT ON expense.approvals TO chama_app;

ALTER TABLE expense.expenses ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON expense.expenses
    USING (public.app_rls_bypass() OR tenant_id = public.app_current_tenant())
    WITH CHECK (public.app_rls_bypass() OR tenant_id = public.app_current_tenant());

ALTER TABLE expense.approvals ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON expense.approvals
    USING (public.app_rls_bypass() OR tenant_id = public.app_current_tenant())
    WITH CHECK (public.app_rls_bypass() OR tenant_id = public.app_current_tenant());
