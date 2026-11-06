-- Phase 3: double-entry ledger, the source of truth for every balance in a chama.
--
-- * accounts: each chama's chart of accounts. "Member ledger" accounts (member savings, loans
--   receivable) carry the member on every line, giving each member their own balance.
-- * journal_entries + journal_lines: every money movement is one balanced entry (debits =
--   credits). Append-only: a mistake is corrected by a reversal entry, never by editing.
-- * entry_sequences: gap-free entry numbers per chama (#1, #2, ...).
-- Every table is tenant-owned and protected by row-level security, like V9.

CREATE SCHEMA IF NOT EXISTS ledger;

CREATE TABLE ledger.accounts
(
    id            UUID PRIMARY KEY,
    tenant_id     UUID         NOT NULL DEFAULT public.app_current_tenant(),
    code          VARCHAR(10)  NOT NULL CHECK (code ~ '^[0-9A-Z][0-9A-Z-]{1,9}$'),
    name          VARCHAR(100) NOT NULL,
    type          VARCHAR(20)  NOT NULL CHECK (type IN ('ASSET', 'LIABILITY', 'EQUITY', 'INCOME', 'EXPENSE')),
    -- Accounts the system posts to (CASH, MEMBER_SAVINGS, ...); null for accounts a chama adds itself.
    system_key    VARCHAR(40),
    currency      VARCHAR(3)   NOT NULL,
    member_ledger BOOLEAN      NOT NULL DEFAULT false,
    active        BOOLEAN      NOT NULL DEFAULT true,
    created_by    UUID,
    created_at    TIMESTAMPTZ  NOT NULL,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, code)
);
CREATE UNIQUE INDEX ux_accounts_system_key ON ledger.accounts (tenant_id, system_key) WHERE system_key IS NOT NULL;

CREATE TABLE ledger.entry_sequences
(
    tenant_id   UUID PRIMARY KEY DEFAULT public.app_current_tenant(),
    last_number BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE ledger.journal_entries
(
    id                UUID PRIMARY KEY,
    tenant_id         UUID         NOT NULL DEFAULT public.app_current_tenant(),
    entry_number      BIGINT       NOT NULL,
    entry_date        DATE         NOT NULL,
    description       VARCHAR(500) NOT NULL,
    reference         VARCHAR(100),
    -- What caused the entry: MANUAL, REVERSAL, and later CONTRIBUTION, LOAN_DISBURSEMENT, ...
    source_type       VARCHAR(40)  NOT NULL,
    source_id         VARCHAR(64),
    reverses_entry_id UUID,
    recorded_by       UUID,
    recorded_at       TIMESTAMPTZ  NOT NULL,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, entry_number),
    FOREIGN KEY (tenant_id, reverses_entry_id) REFERENCES ledger.journal_entries (tenant_id, id)
);
-- A source (one contribution, one loan disbursement) is posted at most once ...
CREATE UNIQUE INDEX ux_entries_source ON ledger.journal_entries (tenant_id, source_type, source_id)
    WHERE source_id IS NOT NULL;
-- ... and an entry is reversed at most once.
CREATE UNIQUE INDEX ux_entries_reverses ON ledger.journal_entries (reverses_entry_id)
    WHERE reverses_entry_id IS NOT NULL;
CREATE INDEX idx_entries_tenant_date ON ledger.journal_entries (tenant_id, entry_date, entry_number);

CREATE TABLE ledger.journal_lines
(
    id             UUID PRIMARY KEY,
    tenant_id      UUID         NOT NULL DEFAULT public.app_current_tenant(),
    entry_id       UUID         NOT NULL,
    line_number    INTEGER      NOT NULL,
    account_id     UUID         NOT NULL,
    debit_minor    BIGINT       NOT NULL DEFAULT 0 CHECK (debit_minor >= 0),
    credit_minor   BIGINT       NOT NULL DEFAULT 0 CHECK (credit_minor >= 0),
    member_user_id UUID,
    memo           VARCHAR(200),
    -- Exactly one side per line.
    CHECK ((debit_minor > 0) <> (credit_minor > 0)),
    UNIQUE (entry_id, line_number),
    -- Composite keys: a line can only use an entry and an account of its own chama.
    FOREIGN KEY (tenant_id, entry_id) REFERENCES ledger.journal_entries (tenant_id, id),
    FOREIGN KEY (tenant_id, account_id) REFERENCES ledger.accounts (tenant_id, id)
);
CREATE INDEX idx_lines_account ON ledger.journal_lines (tenant_id, account_id);
CREATE INDEX idx_lines_member ON ledger.journal_lines (tenant_id, member_user_id, account_id)
    WHERE member_user_id IS NOT NULL;

-- ---- Every entry balances, checked when the transaction commits ---------------------------

CREATE FUNCTION ledger.check_entry_balanced() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    target  uuid := CASE WHEN TG_TABLE_NAME = 'journal_entries' THEN NEW.id ELSE NEW.entry_id END;
    debits  bigint;
    credits bigint;
    lines   integer;
BEGIN
    SELECT coalesce(sum(debit_minor), 0), coalesce(sum(credit_minor), 0), count(*)
      INTO debits, credits, lines
      FROM ledger.journal_lines
     WHERE entry_id = target;
    IF lines < 2 OR debits <> credits THEN
        RAISE EXCEPTION 'ledger entry % is not balanced (debits %, credits %, lines %)', target, debits, credits, lines
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER journal_entry_balanced
    AFTER INSERT ON ledger.journal_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger.check_entry_balanced();

CREATE CONSTRAINT TRIGGER journal_line_balanced
    AFTER INSERT ON ledger.journal_lines
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger.check_entry_balanced();

-- ---- Append-only, like the audit log ---------------------------------------------------------

CREATE FUNCTION ledger.reject_modification() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'ledger.% is append-only (% rejected); post a reversal entry instead', TG_TABLE_NAME, TG_OP;
END;
$$;

CREATE TRIGGER journal_entries_no_update_delete BEFORE UPDATE OR DELETE ON ledger.journal_entries
    FOR EACH ROW EXECUTE FUNCTION ledger.reject_modification();
CREATE TRIGGER journal_entries_no_truncate BEFORE TRUNCATE ON ledger.journal_entries
    FOR EACH STATEMENT EXECUTE FUNCTION ledger.reject_modification();
CREATE TRIGGER journal_lines_no_update_delete BEFORE UPDATE OR DELETE ON ledger.journal_lines
    FOR EACH ROW EXECUTE FUNCTION ledger.reject_modification();
CREATE TRIGGER journal_lines_no_truncate BEFORE TRUNCATE ON ledger.journal_lines
    FOR EACH STATEMENT EXECUTE FUNCTION ledger.reject_modification();

-- ---- Privileges and row-level security --------------------------------------------------------

GRANT USAGE ON SCHEMA ledger TO chama_app;
GRANT SELECT, INSERT, UPDATE ON ledger.accounts, ledger.entry_sequences TO chama_app;
GRANT SELECT, INSERT ON ledger.journal_entries, ledger.journal_lines TO chama_app;

ALTER TABLE ledger.accounts ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ledger.accounts
    USING (public.app_rls_bypass() OR tenant_id = public.app_current_tenant())
    WITH CHECK (public.app_rls_bypass() OR tenant_id = public.app_current_tenant());

ALTER TABLE ledger.entry_sequences ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ledger.entry_sequences
    USING (public.app_rls_bypass() OR tenant_id = public.app_current_tenant())
    WITH CHECK (public.app_rls_bypass() OR tenant_id = public.app_current_tenant());

ALTER TABLE ledger.journal_entries ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ledger.journal_entries
    USING (public.app_rls_bypass() OR tenant_id = public.app_current_tenant())
    WITH CHECK (public.app_rls_bypass() OR tenant_id = public.app_current_tenant());

ALTER TABLE ledger.journal_lines ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ledger.journal_lines
    USING (public.app_rls_bypass() OR tenant_id = public.app_current_tenant())
    WITH CHECK (public.app_rls_bypass() OR tenant_id = public.app_current_tenant());

-- ---- Existing chamas ---------------------------------------------------------------------------

-- New chamas get their chart from DefaultChartOfAccounts when they are created. Chamas created
-- before this migration get the same chart here (keep the two in step for this one backfill).
INSERT INTO ledger.accounts (id, tenant_id, code, name, type, system_key, currency, member_ledger, created_at)
SELECT gen_random_uuid(), c.id, a.code, a.name, a.type, a.system_key, c.currency, a.member_ledger, now()
  FROM chama.chamas c
 CROSS JOIN (VALUES
        ('1000', 'Cash on hand',                 'ASSET',     'CASH',               false),
        ('1010', 'Bank account',                 'ASSET',     'BANK',               false),
        ('1020', 'Mobile money (M-Pesa)',        'ASSET',     'MOBILE_MONEY',       false),
        ('1100', 'Loans receivable',             'ASSET',     'LOANS_RECEIVABLE',   true),
        ('1200', 'Investments',                  'ASSET',     'INVESTMENTS',        false),
        ('2000', 'Member savings',               'LIABILITY', 'MEMBER_SAVINGS',     true),
        ('3000', 'Retained surplus',             'EQUITY',    'RETAINED_SURPLUS',   false),
        ('4000', 'Loan interest income',         'INCOME',    'INTEREST_INCOME',    false),
        ('4100', 'Penalty income',               'INCOME',    'PENALTY_INCOME',     false),
        ('4200', 'Investment income',            'INCOME',    'INVESTMENT_INCOME',  false),
        ('4900', 'Other income',                 'INCOME',    'OTHER_INCOME',       false),
        ('5000', 'Operating expenses',           'EXPENSE',   'OPERATING_EXPENSES', false),
        ('5100', 'Bank and transaction charges', 'EXPENSE',   'BANK_CHARGES',       false)
     ) AS a (code, name, type, system_key, member_ledger);

INSERT INTO ledger.entry_sequences (tenant_id, last_number)
SELECT id, 0 FROM chama.chamas;

-- The new ledger permissions go to the roles that get them by default (DefaultRolePermissions).
INSERT INTO member.role_permissions (tenant_id, role, permission)
SELECT c.id, r.role, p.permission
  FROM chama.chamas c
 CROSS JOIN (VALUES ('CHAIRPERSON'), ('TREASURER')) AS r (role)
 CROSS JOIN (VALUES ('LEDGER_POST'), ('LEDGER_ACCOUNTS_MANAGE')) AS p (permission)
ON CONFLICT DO NOTHING;
