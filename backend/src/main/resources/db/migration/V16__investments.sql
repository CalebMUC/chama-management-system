-- Phase 8: investments. An official proposes an investment; a quorum of other officials approves
-- it; it is funded, earns income, may be partly withdrawn, valued, and finally closed. Money
-- movements post to the ledger against 1200 Investments and 4200 Investment income.

-- New permission INVESTMENT_APPROVE: the chairperson (who holds every permission) and the
-- secretary get it, as with expenses, so the default quorum of two can be met.
INSERT INTO member.role_permissions (tenant_id, role, permission)
SELECT c.id, r.role, 'INVESTMENT_APPROVE'
  FROM chama.chamas c
 CROSS JOIN (VALUES ('CHAIRPERSON'), ('SECRETARY')) AS r (role)
ON CONFLICT DO NOTHING;

CREATE SCHEMA IF NOT EXISTS investment;

CREATE TABLE investment.investments
(
    id                     UUID PRIMARY KEY,
    tenant_id              UUID         NOT NULL DEFAULT public.app_current_tenant(),
    name                   VARCHAR(120) NOT NULL,
    type                   VARCHAR(20)  NOT NULL CHECK (type IN ('MONEY_MARKET_FUND', 'FIXED_DEPOSIT', 'TREASURY_BILL',
                                                                 'TREASURY_BOND', 'SACCO_SHARES', 'EQUITIES', 'LAND',
                                                                 'BUSINESS', 'OTHER')),
    institution            VARCHAR(120),
    description            VARCHAR(300),
    amount_minor           BIGINT       NOT NULL CHECK (amount_minor > 0),
    currency               VARCHAR(3)   NOT NULL,
    expected_return_bp     INT CHECK (expected_return_bp >= 0),
    maturity_date          DATE,
    proposed_by            UUID         NOT NULL,
    required_approvals     INT          NOT NULL CHECK (required_approvals >= 1),
    status                 VARCHAR(12)  NOT NULL CHECK (status IN ('PROPOSED', 'APPROVED', 'ACTIVE', 'CLOSED', 'REJECTED',
                                                                   'CANCELLED')),
    rejected_by            UUID,
    rejected_at            TIMESTAMPTZ,
    rejection_reason       VARCHAR(300),
    cancelled_at           TIMESTAMPTZ,
    funded_on              DATE,
    -- At cost: put in + reinvested income - withdrawals. Zero once closed.
    book_value_minor       BIGINT       NOT NULL DEFAULT 0 CHECK (book_value_minor >= 0),
    income_minor           BIGINT       NOT NULL DEFAULT 0 CHECK (income_minor >= 0),
    withdrawn_minor        BIGINT       NOT NULL DEFAULT 0 CHECK (withdrawn_minor >= 0),
    latest_valuation_minor BIGINT CHECK (latest_valuation_minor >= 0),
    latest_valuation_on    DATE,
    closed_on              DATE,
    closing_proceeds_minor BIGINT CHECK (closing_proceeds_minor >= 0),
    realised_gain_minor    BIGINT,
    created_at             TIMESTAMPTZ  NOT NULL,
    version                BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id)
);
CREATE INDEX idx_investments_tenant_status ON investment.investments (tenant_id, status, created_at DESC);

-- One row per official's approval; never changed.
CREATE TABLE investment.approvals
(
    tenant_id        UUID        NOT NULL DEFAULT public.app_current_tenant(),
    investment_id    UUID        NOT NULL,
    approver_user_id UUID        NOT NULL,
    note             VARCHAR(300),
    approved_at      TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, investment_id, approver_user_id),
    FOREIGN KEY (tenant_id, investment_id) REFERENCES investment.investments (tenant_id, id)
);

-- Money in and out: funding, income, withdrawals and the closing proceeds. Never changed.
CREATE TABLE investment.transactions
(
    id              UUID PRIMARY KEY,
    tenant_id       UUID         NOT NULL DEFAULT public.app_current_tenant(),
    investment_id   UUID         NOT NULL,
    kind            VARCHAR(12)  NOT NULL CHECK (kind IN ('FUNDING', 'INCOME', 'WITHDRAWAL', 'CLOSURE')),
    amount_minor    BIGINT       NOT NULL CHECK (amount_minor >= 0),
    currency        VARCHAR(3)   NOT NULL,
    method          VARCHAR(10)  NOT NULL CHECK (method IN ('CASH', 'BANK', 'MPESA', 'REINVESTED')),
    reference       VARCHAR(100),
    transaction_on  DATE         NOT NULL,
    note            VARCHAR(300),
    ledger_entry_id UUID         NOT NULL,
    recorded_by     UUID         NOT NULL,
    recorded_at     TIMESTAMPTZ  NOT NULL,
    FOREIGN KEY (tenant_id, investment_id) REFERENCES investment.investments (tenant_id, id)
);
CREATE INDEX idx_investment_transactions ON investment.transactions (tenant_id, investment_id, transaction_on);
CREATE UNIQUE INDEX ux_investment_transactions_reference ON investment.transactions (tenant_id, method, upper(reference))
    WHERE reference IS NOT NULL;

-- What an investment was worth on a date. Informational: nothing is posted. Never changed.
CREATE TABLE investment.valuations
(
    id            UUID PRIMARY KEY,
    tenant_id     UUID         NOT NULL DEFAULT public.app_current_tenant(),
    investment_id UUID         NOT NULL,
    value_minor   BIGINT       NOT NULL CHECK (value_minor >= 0),
    valued_on     DATE         NOT NULL,
    note          VARCHAR(300),
    recorded_by   UUID         NOT NULL,
    recorded_at   TIMESTAMPTZ  NOT NULL,
    FOREIGN KEY (tenant_id, investment_id) REFERENCES investment.investments (tenant_id, id)
);
CREATE INDEX idx_investment_valuations ON investment.valuations (tenant_id, investment_id, valued_on);

GRANT USAGE ON SCHEMA investment TO chama_app;
GRANT SELECT, INSERT, UPDATE ON investment.investments TO chama_app;
GRANT SELECT, INSERT ON investment.approvals TO chama_app;
GRANT SELECT, INSERT ON investment.transactions TO chama_app;
GRANT SELECT, INSERT ON investment.valuations TO chama_app;

ALTER TABLE investment.investments ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON investment.investments
    USING (public.app_rls_bypass() OR tenant_id = public.app_current_tenant())
    WITH CHECK (public.app_rls_bypass() OR tenant_id = public.app_current_tenant());

ALTER TABLE investment.approvals ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON investment.approvals
    USING (public.app_rls_bypass() OR tenant_id = public.app_current_tenant())
    WITH CHECK (public.app_rls_bypass() OR tenant_id = public.app_current_tenant());

ALTER TABLE investment.transactions ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON investment.transactions
    USING (public.app_rls_bypass() OR tenant_id = public.app_current_tenant())
    WITH CHECK (public.app_rls_bypass() OR tenant_id = public.app_current_tenant());

ALTER TABLE investment.valuations ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON investment.valuations
    USING (public.app_rls_bypass() OR tenant_id = public.app_current_tenant())
    WITH CHECK (public.app_rls_bypass() OR tenant_id = public.app_current_tenant());
