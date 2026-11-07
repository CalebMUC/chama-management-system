-- Phase 9: bringing a chama's records over from Excel. Opening balances are imported once per
-- chama, as at a cut-over date; this table records that import and its totals.

CREATE SCHEMA IF NOT EXISTS dataimport;

CREATE TABLE dataimport.opening_balance_imports
(
    id                     UUID PRIMARY KEY,
    tenant_id              UUID         NOT NULL DEFAULT public.app_current_tenant() UNIQUE,
    as_of                  DATE         NOT NULL,
    file_name              VARCHAR(255),
    imported_by            UUID         NOT NULL,
    imported_at            TIMESTAMPTZ  NOT NULL,
    -- The opening-balance journal entry; null if the file brought forward no money (members only).
    ledger_entry_id        UUID,
    currency               VARCHAR(3)   NOT NULL,
    new_members            INT          NOT NULL,
    existing_members       INT          NOT NULL,
    savings_minor          BIGINT       NOT NULL,
    loans                  INT          NOT NULL,
    loans_minor            BIGINT       NOT NULL,
    penalties_minor        BIGINT       NOT NULL,
    cash_minor             BIGINT       NOT NULL,
    bank_minor             BIGINT       NOT NULL,
    mobile_money_minor     BIGINT       NOT NULL,
    investments            INT          NOT NULL,
    investments_minor      BIGINT       NOT NULL,
    retained_surplus_minor BIGINT       NOT NULL
);

GRANT USAGE ON SCHEMA dataimport TO chama_app;
-- An import is never changed or undone.
GRANT SELECT, INSERT ON dataimport.opening_balance_imports TO chama_app;

ALTER TABLE dataimport.opening_balance_imports ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON dataimport.opening_balance_imports
    USING (public.app_rls_bypass() OR tenant_id = public.app_current_tenant())
    WITH CHECK (public.app_rls_bypass() OR tenant_id = public.app_current_tenant());

-- Imported investments carry a funding transaction marked as brought forward.
ALTER TABLE investment.transactions DROP CONSTRAINT transactions_method_check;
ALTER TABLE investment.transactions
    ADD CONSTRAINT transactions_method_check CHECK (method IN ('CASH', 'BANK', 'MPESA', 'REINVESTED', 'OPENING_BALANCE'));
