-- Member self-service: members report their own payments for an official to confirm, and plain
-- members no longer see the member directory or the role-permission matrix.

-- ---- Permissions --------------------------------------------------------------------------------

-- The member directory (GET /members) and the role matrix (GET /roles) are for officials. Plain
-- members pick guarantors from GET /loans/guarantor-candidates instead.
-- Officials' roles keep MEMBER_VIEW as they have it.
DELETE FROM member.role_permissions WHERE role = 'MEMBER' AND permission = 'MEMBER_VIEW';

-- Every role may report its own payments.
INSERT INTO member.role_permissions (tenant_id, role, permission)
SELECT c.id, r.role, 'PAYMENT_SUBMIT'
  FROM chama.chamas c
 CROSS JOIN (VALUES ('MEMBER'), ('TREASURER'), ('SECRETARY'), ('CHAIRPERSON'), ('AUDITOR')) AS r (role)
ON CONFLICT DO NOTHING;

-- ---- Payment submissions ------------------------------------------------------------------------

CREATE SCHEMA IF NOT EXISTS submission;

-- A payment a member says they made. Moves no money: approving it records the real contribution,
-- penalty payment or loan repayment in its own module (and the ledger) in the same transaction.
CREATE TABLE submission.payment_submissions
(
    id             UUID PRIMARY KEY,
    tenant_id      UUID         NOT NULL DEFAULT public.app_current_tenant(),
    member_user_id UUID         NOT NULL,
    kind           VARCHAR(20)  NOT NULL CHECK (kind IN ('CONTRIBUTION', 'PENALTY_PAYMENT', 'LOAN_REPAYMENT')),
    -- The penalty or loan paid towards; none for a contribution.
    target_id      UUID,
    amount_minor   BIGINT       NOT NULL CHECK (amount_minor > 0),
    currency       VARCHAR(3)   NOT NULL,
    method         VARCHAR(10)  NOT NULL CHECK (method IN ('CASH', 'BANK', 'MPESA')),
    reference      VARCHAR(100),
    paid_on        DATE         NOT NULL,
    notes          VARCHAR(300),
    submitted_at   TIMESTAMPTZ  NOT NULL,
    status         VARCHAR(10)  NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    reviewed_by    UUID,
    reviewed_at    TIMESTAMPTZ,
    -- The approver's note, or why it was rejected.
    review_note    VARCHAR(300),
    cancelled_at   TIMESTAMPTZ,
    version        BIGINT       NOT NULL DEFAULT 0,
    CHECK ((kind = 'CONTRIBUTION') = (target_id IS NULL)),
    CHECK (method = 'CASH' OR reference IS NOT NULL),
    CHECK (status NOT IN ('APPROVED', 'REJECTED') OR (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL)),
    CHECK (status <> 'REJECTED' OR review_note IS NOT NULL),
    CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL))
);
-- One M-Pesa code or bank slip is reported once (unless the report was rejected or cancelled).
CREATE UNIQUE INDEX ux_payment_submissions_reference ON submission.payment_submissions (tenant_id, method, upper(reference))
    WHERE reference IS NOT NULL AND status IN ('PENDING', 'APPROVED');
CREATE INDEX idx_payment_submissions_queue ON submission.payment_submissions (tenant_id, status, kind, submitted_at);
CREATE INDEX idx_payment_submissions_member ON submission.payment_submissions (tenant_id, member_user_id, submitted_at DESC);

GRANT USAGE ON SCHEMA submission TO chama_app;
-- Reviewed or cancelled, never deleted.
GRANT SELECT, INSERT, UPDATE ON submission.payment_submissions TO chama_app;

ALTER TABLE submission.payment_submissions ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON submission.payment_submissions
    USING (public.app_rls_bypass() OR tenant_id = public.app_current_tenant())
    WITH CHECK (public.app_rls_bypass() OR tenant_id = public.app_current_tenant());
