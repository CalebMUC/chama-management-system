-- ============================================================
-- V26 — Member exits
-- A member leaves the chama through a settlement that officials approve:
--   * refundable savings are paid out, after their loans and penalties are cleared from them;
--   * non-refundable balances (share capital, welfare) are forfeited to the chama;
--   * no dividend is paid for the year they leave.
-- A member who guarantees another member's loan cannot leave until the guarantee is transferred
-- to a new guarantor, so guarantors can now be replaced on a loan.
-- ============================================================

-- ---- 1. Membership: when, why and by whom the member left -------------------------------------

ALTER TABLE member.memberships ADD COLUMN exited_at   TIMESTAMPTZ;
ALTER TABLE member.memberships ADD COLUMN exited_by   UUID;
ALTER TABLE member.memberships ADD COLUMN exit_reason VARCHAR(300);

-- ---- 2. Loans: replacing a guarantor ----------------------------------------------------------

-- RELEASED: a guarantor whose guarantee passed to a replacement who accepted.
ALTER TABLE loan.guarantors DROP CONSTRAINT guarantors_status_check;
ALTER TABLE loan.guarantors ADD CONSTRAINT guarantors_status_check
    CHECK (status IN ('REQUESTED', 'ACCEPTED', 'DECLINED', 'WITHDRAWN', 'RELEASED'));
-- Set on a request to take over another guarantor's guarantee: the guarantor being replaced.
ALTER TABLE loan.guarantors ADD COLUMN replaces_user_id UUID;

-- ---- 3. Loan repayments and penalty payments taken from the member's savings ------------------

ALTER TABLE loan.repayments DROP CONSTRAINT repayments_method_check;
ALTER TABLE loan.repayments ADD CONSTRAINT repayments_method_check
    CHECK (method IN ('CASH', 'BANK', 'MPESA', 'SAVINGS'));

ALTER TABLE penalty.payments DROP CONSTRAINT payments_method_check;
ALTER TABLE penalty.payments ADD CONSTRAINT payments_method_check
    CHECK (method IN ('CASH', 'BANK', 'MPESA', 'SAVINGS'));

-- ---- 4. Exit settlements ----------------------------------------------------------------------

CREATE SCHEMA IF NOT EXISTS member_exit;

CREATE TABLE member_exit.exits
(
    id                 UUID PRIMARY KEY,
    tenant_id          UUID         NOT NULL DEFAULT public.app_current_tenant(),
    member_user_id     UUID         NOT NULL,
    reason             VARCHAR(300) NOT NULL,
    currency           VARCHAR(3)   NOT NULL,
    -- The settlement as officials approve it; checked again, unchanged, when it is paid.
    refundable_minor   BIGINT       NOT NULL CHECK (refundable_minor >= 0),
    forfeited_minor    BIGINT       NOT NULL CHECK (forfeited_minor >= 0),
    loans_minor        BIGINT       NOT NULL CHECK (loans_minor >= 0),
    penalties_minor    BIGINT       NOT NULL CHECK (penalties_minor >= 0),
    payout_minor       BIGINT       NOT NULL CHECK (payout_minor >= 0),
    requested_by       UUID         NOT NULL,
    required_approvals INT          NOT NULL CHECK (required_approvals >= 1),
    status             VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'SETTLED', 'REJECTED',
                                                               'CANCELLED')),
    rejected_by        UUID,
    rejected_at        TIMESTAMPTZ,
    rejection_reason   VARCHAR(300),
    cancelled_at       TIMESTAMPTZ,
    settled_on         DATE,
    settled_by         UUID,
    payment_method     VARCHAR(10) CHECK (payment_method IN ('CASH', 'BANK', 'MPESA')),
    payment_reference  VARCHAR(100),
    settlement_entry_id UUID,
    created_at         TIMESTAMPTZ  NOT NULL,
    version            BIGINT       NOT NULL DEFAULT 0,
    CHECK (payout_minor = refundable_minor - loans_minor - penalties_minor),
    CHECK (status <> 'SETTLED' OR settled_on IS NOT NULL),
    UNIQUE (tenant_id, id)
);
-- One exit in progress per member.
CREATE UNIQUE INDEX ux_exits_one_open ON member_exit.exits (tenant_id, member_user_id)
    WHERE status IN ('PENDING_APPROVAL', 'APPROVED');
CREATE INDEX idx_exits_tenant_status ON member_exit.exits (tenant_id, status, created_at DESC);
CREATE UNIQUE INDEX ux_exits_payment_reference ON member_exit.exits (tenant_id, payment_method, upper(payment_reference))
    WHERE payment_reference IS NOT NULL;

-- The settlement statement, line by line, as approved.
CREATE TABLE member_exit.lines
(
    tenant_id    UUID         NOT NULL DEFAULT public.app_current_tenant(),
    exit_id      UUID         NOT NULL,
    line_no      INT          NOT NULL,
    -- REFUND (refundable savings), FORFEIT (non-refundable savings), LOAN or PENALTY (owed, cleared from savings)
    kind         VARCHAR(10)  NOT NULL CHECK (kind IN ('REFUND', 'FORFEIT', 'LOAN', 'PENALTY')),
    -- The savings product, loan or penalty.
    source_id    UUID         NOT NULL,
    -- The savings product's ledger account (REFUND, FORFEIT).
    account_code VARCHAR(10),
    description  VARCHAR(300) NOT NULL,
    amount_minor BIGINT       NOT NULL CHECK (amount_minor > 0),
    PRIMARY KEY (tenant_id, exit_id, line_no),
    FOREIGN KEY (tenant_id, exit_id) REFERENCES member_exit.exits (tenant_id, id)
);

-- One row per official's approval; never changed.
CREATE TABLE member_exit.approvals
(
    tenant_id        UUID        NOT NULL DEFAULT public.app_current_tenant(),
    exit_id          UUID        NOT NULL,
    approver_user_id UUID        NOT NULL,
    note             VARCHAR(300),
    approved_at      TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, exit_id, approver_user_id),
    FOREIGN KEY (tenant_id, exit_id) REFERENCES member_exit.exits (tenant_id, id)
);

GRANT USAGE ON SCHEMA member_exit TO chama_app;
-- Exits change as they are approved and settled; nothing is ever deleted.
GRANT SELECT, INSERT, UPDATE ON member_exit.exits TO chama_app;
GRANT SELECT, INSERT ON member_exit.lines TO chama_app;
GRANT SELECT, INSERT ON member_exit.approvals TO chama_app;

ALTER TABLE member_exit.exits ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON member_exit.exits
    USING (public.app_rls_bypass() OR tenant_id = public.app_current_tenant())
    WITH CHECK (public.app_rls_bypass() OR tenant_id = public.app_current_tenant());

ALTER TABLE member_exit.lines ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON member_exit.lines
    USING (public.app_rls_bypass() OR tenant_id = public.app_current_tenant())
    WITH CHECK (public.app_rls_bypass() OR tenant_id = public.app_current_tenant());

ALTER TABLE member_exit.approvals ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON member_exit.approvals
    USING (public.app_rls_bypass() OR tenant_id = public.app_current_tenant())
    WITH CHECK (public.app_rls_bypass() OR tenant_id = public.app_current_tenant());

-- ---- 5. Permissions -----------------------------------------------------------------------------
-- Keep in step with DefaultRolePermissions, which seeds new chamas. Separation of duties as for
-- expenses: the treasurer (or admin) starts an exit and the treasurer pays it out; the chairperson
-- and secretary approve it (two approvers, for the default quorum of two).

INSERT INTO member.role_permissions (tenant_id, role, permission)
SELECT c.id, r.role, r.permission
  FROM chama.chamas c
 CROSS JOIN (VALUES ('TREASURER', 'MEMBER_EXIT_REQUEST'),
                    ('TREASURER', 'MEMBER_EXIT_SETTLE'),
                    ('ADMIN', 'MEMBER_EXIT_REQUEST'),
                    ('SECRETARY', 'MEMBER_EXIT_APPROVE'),
                    ('CHAIRPERSON', 'MEMBER_EXIT_REQUEST'),
                    ('CHAIRPERSON', 'MEMBER_EXIT_APPROVE'),
                    ('CHAIRPERSON', 'MEMBER_EXIT_SETTLE')) AS r (role, permission)
ON CONFLICT DO NOTHING;
