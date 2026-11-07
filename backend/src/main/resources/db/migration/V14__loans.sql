-- Phase 6: loans. A member applies; guarantors accept (for loans above the chama's threshold); a
-- quorum of officials approves; an official disburses; repayments follow. Disbursement and
-- repayments post to the ledger against the borrower's "1100 Loans receivable". The repayment
-- schedule is derived from the loan (equal monthly instalments), so it is not stored.

CREATE SCHEMA IF NOT EXISTS loan;

CREATE TABLE loan.loans
(
    id                     UUID PRIMARY KEY,
    tenant_id              UUID         NOT NULL DEFAULT public.app_current_tenant(),
    borrower_user_id       UUID         NOT NULL,
    principal_minor        BIGINT       NOT NULL CHECK (principal_minor > 0),
    -- The chama's rules when the member applied: later changes do not alter an existing loan.
    interest_bp            INT          NOT NULL CHECK (interest_bp >= 0),
    interest_minor         BIGINT       NOT NULL CHECK (interest_minor >= 0),
    term_months            INT          NOT NULL CHECK (term_months BETWEEN 1 AND 36),
    purpose                VARCHAR(300) NOT NULL,
    currency               VARCHAR(3)   NOT NULL,
    applied_on             DATE         NOT NULL,
    required_guarantors    INT          NOT NULL CHECK (required_guarantors >= 0),
    required_approvals     INT          NOT NULL CHECK (required_approvals >= 1),
    status                 VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING_GUARANTORS', 'PENDING_APPROVAL', 'APPROVED',
                                                                   'ACTIVE', 'REPAID', 'REJECTED', 'CANCELLED')),
    rejected_by            UUID,
    rejected_at            TIMESTAMPTZ,
    rejection_reason       VARCHAR(300),
    cancelled_at           TIMESTAMPTZ,
    disbursed_on           DATE,
    disbursed_by           UUID,
    disbursement_method    VARCHAR(10) CHECK (disbursement_method IN ('CASH', 'BANK', 'MPESA')),
    disbursement_reference VARCHAR(100),
    disbursement_entry_id  UUID,
    repaid_minor           BIGINT       NOT NULL DEFAULT 0 CHECK (repaid_minor >= 0),
    created_at             TIMESTAMPTZ  NOT NULL,
    version                BIGINT       NOT NULL DEFAULT 0,
    CHECK (repaid_minor <= principal_minor + interest_minor),
    CHECK (status NOT IN ('ACTIVE', 'REPAID') OR disbursement_entry_id IS NOT NULL),
    UNIQUE (tenant_id, id)
);
-- One loan in progress per member: a member clears a loan before applying for the next.
CREATE UNIQUE INDEX ux_loans_one_open ON loan.loans (tenant_id, borrower_user_id)
    WHERE status IN ('PENDING_GUARANTORS', 'PENDING_APPROVAL', 'APPROVED', 'ACTIVE');
CREATE INDEX idx_loans_tenant_status ON loan.loans (tenant_id, status, applied_on DESC);
CREATE INDEX idx_loans_tenant_borrower ON loan.loans (tenant_id, borrower_user_id);
CREATE UNIQUE INDEX ux_loans_disbursement_reference ON loan.loans (tenant_id, disbursement_method, upper(disbursement_reference))
    WHERE disbursement_reference IS NOT NULL;

CREATE TABLE loan.guarantors
(
    tenant_id         UUID        NOT NULL DEFAULT public.app_current_tenant(),
    loan_id           UUID        NOT NULL,
    guarantor_user_id UUID        NOT NULL,
    status            VARCHAR(12) NOT NULL CHECK (status IN ('REQUESTED', 'ACCEPTED', 'DECLINED', 'WITHDRAWN')),
    requested_at      TIMESTAMPTZ NOT NULL,
    responded_at      TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, loan_id, guarantor_user_id),
    FOREIGN KEY (tenant_id, loan_id) REFERENCES loan.loans (tenant_id, id)
);
CREATE INDEX idx_loan_guarantors_user ON loan.guarantors (tenant_id, guarantor_user_id);

-- One row per official's approval; never changed.
CREATE TABLE loan.approvals
(
    tenant_id        UUID         NOT NULL DEFAULT public.app_current_tenant(),
    loan_id          UUID         NOT NULL,
    approver_user_id UUID         NOT NULL,
    note             VARCHAR(300),
    approved_at      TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (tenant_id, loan_id, approver_user_id),
    FOREIGN KEY (tenant_id, loan_id) REFERENCES loan.loans (tenant_id, id)
);

CREATE TABLE loan.repayments
(
    id               UUID PRIMARY KEY,
    tenant_id        UUID         NOT NULL DEFAULT public.app_current_tenant(),
    loan_id          UUID         NOT NULL,
    borrower_user_id UUID         NOT NULL,
    amount_minor     BIGINT       NOT NULL CHECK (amount_minor > 0),
    currency         VARCHAR(3)   NOT NULL,
    method           VARCHAR(10)  NOT NULL CHECK (method IN ('CASH', 'BANK', 'MPESA')),
    reference        VARCHAR(100),
    paid_on          DATE         NOT NULL,
    ledger_entry_id  UUID         NOT NULL,
    recorded_by      UUID         NOT NULL,
    recorded_at      TIMESTAMPTZ  NOT NULL,
    FOREIGN KEY (tenant_id, loan_id) REFERENCES loan.loans (tenant_id, id)
);
CREATE UNIQUE INDEX ux_loan_repayments_reference ON loan.repayments (tenant_id, method, upper(reference))
    WHERE reference IS NOT NULL;
CREATE INDEX idx_loan_repayments_loan ON loan.repayments (tenant_id, loan_id);

-- ---- Privileges and row-level security --------------------------------------------------------

GRANT USAGE ON SCHEMA loan TO chama_app;
-- Loans and guarantor answers change as the loan progresses; nothing is ever deleted.
GRANT SELECT, INSERT, UPDATE ON loan.loans TO chama_app;
GRANT SELECT, INSERT, UPDATE ON loan.guarantors TO chama_app;
GRANT SELECT, INSERT ON loan.approvals TO chama_app;
GRANT SELECT, INSERT ON loan.repayments TO chama_app;

ALTER TABLE loan.loans ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON loan.loans
    USING (public.app_rls_bypass() OR tenant_id = public.app_current_tenant())
    WITH CHECK (public.app_rls_bypass() OR tenant_id = public.app_current_tenant());

ALTER TABLE loan.guarantors ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON loan.guarantors
    USING (public.app_rls_bypass() OR tenant_id = public.app_current_tenant())
    WITH CHECK (public.app_rls_bypass() OR tenant_id = public.app_current_tenant());

ALTER TABLE loan.approvals ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON loan.approvals
    USING (public.app_rls_bypass() OR tenant_id = public.app_current_tenant())
    WITH CHECK (public.app_rls_bypass() OR tenant_id = public.app_current_tenant());

ALTER TABLE loan.repayments ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON loan.repayments
    USING (public.app_rls_bypass() OR tenant_id = public.app_current_tenant())
    WITH CHECK (public.app_rls_bypass() OR tenant_id = public.app_current_tenant());
