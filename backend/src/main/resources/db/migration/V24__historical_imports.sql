-- Historical imports: a chama that ran for years before using the system brings its history over
-- from Excel, one file per module (setup, contributions, loans, penalties, investments, expenses,
-- cash reconciliation). Every file is one batch; a batch can be undone, latest first.
--
-- * dataimport.history_imports records each batch, its totals and what undoing it must restore.
-- * Records created by a batch carry its id (import_batch_id), so the batch can be found and undone.
-- * Periods back-filled for the years before the chama used the system are marked historical: the
--   nightly late-penalty job never penalises them (penalties of those years are imported as recorded).
-- * Undo deletes the batch's business records (contribution payments are reversed instead, as any
--   mistaken payment is) and posts reversal entries: the ledger itself stays append-only.

CREATE TABLE dataimport.history_imports
(
    id          UUID PRIMARY KEY,
    tenant_id   UUID         NOT NULL DEFAULT public.app_current_tenant(),
    kind        VARCHAR(20)  NOT NULL CHECK (kind IN ('SETUP', 'CONTRIBUTIONS', 'LOANS', 'PENALTIES', 'INVESTMENTS',
                                                      'EXPENSES', 'RECONCILIATION')),
    file_name   VARCHAR(255),
    row_count   INT          NOT NULL,
    -- Totals shown on the import's page: [{"label": ..., "type": "AMOUNT|COUNT|TEXT", "value": ..., "text": ...}].
    summary     JSONB        NOT NULL DEFAULT '[]',
    -- What undoing the batch must restore, e.g. members' join dates before a setup import.
    undo_data   JSONB        NOT NULL DEFAULT '{}',
    imported_by UUID         NOT NULL,
    imported_at TIMESTAMPTZ  NOT NULL,
    undone_by   UUID,
    undone_at   TIMESTAMPTZ,
    CHECK ((undone_by IS NULL) = (undone_at IS NULL))
);
CREATE INDEX idx_history_imports_tenant ON dataimport.history_imports (tenant_id, imported_at DESC);

GRANT SELECT, INSERT, UPDATE ON dataimport.history_imports TO chama_app;

ALTER TABLE dataimport.history_imports ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON dataimport.history_imports
    USING (public.app_rls_bypass() OR tenant_id = public.app_current_tenant())
    WITH CHECK (public.app_rls_bypass() OR tenant_id = public.app_current_tenant());

-- ---- contributions ------------------------------------------------------------------------------

ALTER TABLE contribution.periods ADD COLUMN historical BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE contribution.periods ADD COLUMN import_batch_id UUID;
ALTER TABLE contribution.payments ADD COLUMN import_batch_id UUID;
CREATE INDEX idx_periods_import_batch ON contribution.periods (tenant_id, import_batch_id) WHERE import_batch_id IS NOT NULL;
CREATE INDEX idx_payments_import_batch ON contribution.payments (tenant_id, import_batch_id) WHERE import_batch_id IS NOT NULL;

-- Undoing a setup removes the periods it back-filled, with their dues; and dues that later
-- periods gained when join dates were moved back.
GRANT UPDATE, DELETE ON contribution.periods TO chama_app;
GRANT DELETE ON contribution.period_products, contribution.dues TO chama_app;

-- ---- loans --------------------------------------------------------------------------------------

ALTER TABLE loan.loans ADD COLUMN import_batch_id UUID;
ALTER TABLE loan.repayments ADD COLUMN import_batch_id UUID;
CREATE INDEX idx_loans_import_batch ON loan.loans (tenant_id, import_batch_id) WHERE import_batch_id IS NOT NULL;
GRANT DELETE ON loan.loans, loan.repayments TO chama_app;
-- Repayments stay immutable: only the batch tag can be set.
GRANT UPDATE (import_batch_id) ON loan.repayments TO chama_app;

-- ---- penalties ----------------------------------------------------------------------------------

ALTER TABLE penalty.penalties ADD COLUMN import_batch_id UUID;
ALTER TABLE penalty.payments ADD COLUMN import_batch_id UUID;
CREATE INDEX idx_penalties_import_batch ON penalty.penalties (tenant_id, import_batch_id) WHERE import_batch_id IS NOT NULL;
GRANT DELETE ON penalty.penalties, penalty.payments TO chama_app;
GRANT UPDATE (import_batch_id) ON penalty.payments TO chama_app;

-- ---- investments --------------------------------------------------------------------------------

ALTER TABLE investment.investments ADD COLUMN import_batch_id UUID;
ALTER TABLE investment.transactions ADD COLUMN import_batch_id UUID;
ALTER TABLE investment.valuations ADD COLUMN import_batch_id UUID;
CREATE INDEX idx_investments_import_batch ON investment.investments (tenant_id, import_batch_id) WHERE import_batch_id IS NOT NULL;
GRANT DELETE ON investment.investments, investment.transactions, investment.valuations TO chama_app;
GRANT UPDATE (import_batch_id) ON investment.transactions, investment.valuations TO chama_app;

-- ---- expenses -----------------------------------------------------------------------------------

ALTER TABLE expense.expenses ADD COLUMN import_batch_id UUID;
CREATE INDEX idx_expenses_import_batch ON expense.expenses (tenant_id, import_batch_id) WHERE import_batch_id IS NOT NULL;
GRANT DELETE ON expense.expenses TO chama_app;
