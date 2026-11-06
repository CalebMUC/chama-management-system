-- Phase 5: penalties. Late contributions are penalised automatically; officials can also impose
-- fines. A penalty is owed by one member, can be paid in parts, and can be waived (with a reason).
-- Every step posts to the ledger against the new member account "1110 Penalties receivable".

-- ---- Ledger: the new account for every existing chama ------------------------------------------

INSERT INTO ledger.accounts (id, tenant_id, code, name, type, system_key, currency, member_ledger, created_at)
SELECT gen_random_uuid(), c.id, '1110', 'Penalties receivable', 'ASSET', 'PENALTIES_RECEIVABLE', c.currency, true, now()
  FROM chama.chamas c
 WHERE NOT EXISTS (SELECT 1 FROM ledger.accounts a WHERE a.tenant_id = c.id AND a.system_key = 'PENALTIES_RECEIVABLE')
   AND NOT EXISTS (SELECT 1 FROM ledger.accounts a WHERE a.tenant_id = c.id AND a.code = '1110');

-- The new permission goes to the roles that get it by default (DefaultRolePermissions).
INSERT INTO member.role_permissions (tenant_id, role, permission)
SELECT c.id, r.role, 'PENALTY_RECORD'
  FROM chama.chamas c
 CROSS JOIN (VALUES ('CHAIRPERSON'), ('TREASURER')) AS r (role)
ON CONFLICT DO NOTHING;

-- ---- Penalties --------------------------------------------------------------------------------

CREATE SCHEMA IF NOT EXISTS penalty;

CREATE TABLE penalty.penalties
(
    id                  UUID PRIMARY KEY,
    tenant_id           UUID         NOT NULL DEFAULT public.app_current_tenant(),
    member_user_id      UUID         NOT NULL,
    kind                VARCHAR(20)  NOT NULL CHECK (kind IN ('LATE_CONTRIBUTION', 'FINE')),
    -- What the penalty is for, e.g. LATE_CONTRIBUTION:<period id>: a period is penalised once per member.
    source_key          VARCHAR(80),
    description         VARCHAR(300) NOT NULL,
    amount_minor        BIGINT       NOT NULL CHECK (amount_minor > 0),
    currency            VARCHAR(3)   NOT NULL,
    assessed_on         DATE         NOT NULL,
    assessed_by         UUID,
    assessment_entry_id UUID         NOT NULL,
    paid_minor          BIGINT       NOT NULL DEFAULT 0 CHECK (paid_minor >= 0),
    waived_minor        BIGINT       NOT NULL DEFAULT 0 CHECK (waived_minor >= 0),
    status              VARCHAR(12)  NOT NULL CHECK (status IN ('OUTSTANDING', 'PAID', 'WAIVED')),
    waived_by           UUID,
    waived_at           TIMESTAMPTZ,
    waiver_reason       VARCHAR(300),
    waiver_entry_id     UUID,
    created_at          TIMESTAMPTZ  NOT NULL,
    version             BIGINT       NOT NULL DEFAULT 0,
    CHECK (paid_minor + waived_minor <= amount_minor),
    UNIQUE (tenant_id, id)
);
CREATE UNIQUE INDEX ux_penalties_source ON penalty.penalties (tenant_id, member_user_id, source_key)
    WHERE source_key IS NOT NULL;
CREATE INDEX idx_penalties_tenant_member ON penalty.penalties (tenant_id, member_user_id, status);
CREATE INDEX idx_penalties_tenant_status ON penalty.penalties (tenant_id, status, assessed_on DESC);

CREATE TABLE penalty.payments
(
    id              UUID PRIMARY KEY,
    tenant_id       UUID         NOT NULL DEFAULT public.app_current_tenant(),
    penalty_id      UUID         NOT NULL,
    member_user_id  UUID         NOT NULL,
    amount_minor    BIGINT       NOT NULL CHECK (amount_minor > 0),
    currency        VARCHAR(3)   NOT NULL,
    method          VARCHAR(10)  NOT NULL CHECK (method IN ('CASH', 'BANK', 'MPESA')),
    reference       VARCHAR(100),
    paid_on         DATE         NOT NULL,
    ledger_entry_id UUID         NOT NULL,
    recorded_by     UUID         NOT NULL,
    recorded_at     TIMESTAMPTZ  NOT NULL,
    FOREIGN KEY (tenant_id, penalty_id) REFERENCES penalty.penalties (tenant_id, id)
);
CREATE UNIQUE INDEX ux_penalty_payments_reference ON penalty.payments (tenant_id, method, upper(reference))
    WHERE reference IS NOT NULL;
CREATE INDEX idx_penalty_payments_penalty ON penalty.payments (tenant_id, penalty_id);

-- ---- Privileges and row-level security --------------------------------------------------------

GRANT USAGE ON SCHEMA penalty TO chama_app;
-- Penalties change as they are paid or waived; they are never deleted.
GRANT SELECT, INSERT, UPDATE ON penalty.penalties TO chama_app;
GRANT SELECT, INSERT ON penalty.payments TO chama_app;

ALTER TABLE penalty.penalties ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON penalty.penalties
    USING (public.app_rls_bypass() OR tenant_id = public.app_current_tenant())
    WITH CHECK (public.app_rls_bypass() OR tenant_id = public.app_current_tenant());

ALTER TABLE penalty.payments ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON penalty.payments
    USING (public.app_rls_bypass() OR tenant_id = public.app_current_tenant())
    WITH CHECK (public.app_rls_bypass() OR tenant_id = public.app_current_tenant());
