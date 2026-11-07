-- Phase 10: savings products, loan products and member accounts.
--
-- * account.savings_products: the pots a chama keeps for its members (deposits, share capital,
--   welfare, ...). Each posts to its own member-ledger account in the chart of accounts, so a
--   member's balance in a product is that ledger account's balance for the member.
-- * account.member_accounts: one account per member per savings product, with an account number.
--   Balances are never stored here; they come from the ledger.
-- * contribution: what each product asks per period (period_products), dues per product, and how
--   each payment was split across products (payment_allocations).
-- * loan.products: loan products (normal, emergency, ...) with their own terms. Every loan names one.
--
-- Every existing chama gets a default "Deposits" product on "2000 Member savings" and a default
-- "Normal loan" product with its current settings; every seated member gets a Deposits account;
-- existing dues, payments and loans are linked to the default products. Nothing changes for a
-- chama that keeps a single pot.

CREATE SCHEMA IF NOT EXISTS account;

CREATE TABLE account.savings_products
(
    id                       UUID PRIMARY KEY,
    tenant_id                UUID        NOT NULL DEFAULT public.app_current_tenant(),
    code                     VARCHAR(10) NOT NULL CHECK (code ~ '^[A-Z][A-Z0-9]{1,9}$'),
    name                     VARCHAR(60) NOT NULL,
    kind                     VARCHAR(20) NOT NULL CHECK (kind IN ('DEPOSITS', 'SHARE_CAPITAL', 'WELFARE', 'CUSTOM')),
    -- The member-ledger account in ledger.accounts that holds this product's balances.
    ledger_account_code      VARCHAR(10) NOT NULL,
    -- What each member with an account owes per contribution period; 0 = voluntary top-ups only.
    contribution_minor       BIGINT      NOT NULL CHECK (contribution_minor >= 0),
    -- Lower first: a payment clears this product's dues before those of higher numbers in the same period.
    allocation_priority      INT         NOT NULL CHECK (allocation_priority BETWEEN 1 AND 99),
    -- Every seated member has an account, opened automatically.
    mandatory                BOOLEAN     NOT NULL,
    counts_toward_loan_limit BOOLEAN     NOT NULL,
    refundable_on_exit       BOOLEAN     NOT NULL,
    earns_dividend           BOOLEAN     NOT NULL,
    is_default               BOOLEAN     NOT NULL DEFAULT false,
    active                   BOOLEAN     NOT NULL DEFAULT true,
    created_by               UUID,
    created_at               TIMESTAMPTZ NOT NULL,
    updated_at               TIMESTAMPTZ NOT NULL,
    version                  BIGINT      NOT NULL DEFAULT 0,
    -- The default product takes payments nothing else is owed for, so it is always open to everyone.
    CHECK (NOT is_default OR (active AND mandatory)),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, code),
    UNIQUE (tenant_id, ledger_account_code)
);
CREATE UNIQUE INDEX ux_savings_products_default ON account.savings_products (tenant_id) WHERE is_default;

CREATE TABLE account.member_accounts
(
    id             UUID PRIMARY KEY,
    tenant_id      UUID         NOT NULL DEFAULT public.app_current_tenant(),
    account_number VARCHAR(20)  NOT NULL,
    member_user_id UUID         NOT NULL,
    product_id     UUID         NOT NULL,
    status         VARCHAR(10)  NOT NULL CHECK (status IN ('ACTIVE', 'CLOSED')),
    -- Dues are owed for periods due on or after this day.
    opened_on      DATE         NOT NULL,
    opened_by      UUID,
    closed_on      DATE,
    closed_by      UUID,
    closure_reason VARCHAR(300),
    created_at     TIMESTAMPTZ  NOT NULL,
    version        BIGINT       NOT NULL DEFAULT 0,
    CHECK ((status = 'CLOSED') = (closed_on IS NOT NULL)),
    UNIQUE (tenant_id, product_id, member_user_id),
    UNIQUE (tenant_id, account_number),
    FOREIGN KEY (tenant_id, product_id) REFERENCES account.savings_products (tenant_id, id)
);
CREATE INDEX idx_member_accounts_member ON account.member_accounts (tenant_id, member_user_id);

GRANT USAGE ON SCHEMA account TO chama_app;
GRANT SELECT, INSERT, UPDATE ON account.savings_products, account.member_accounts TO chama_app;

ALTER TABLE account.savings_products ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON account.savings_products
    USING (public.app_rls_bypass() OR tenant_id = public.app_current_tenant())
    WITH CHECK (public.app_rls_bypass() OR tenant_id = public.app_current_tenant());

ALTER TABLE account.member_accounts ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON account.member_accounts
    USING (public.app_rls_bypass() OR tenant_id = public.app_current_tenant())
    WITH CHECK (public.app_rls_bypass() OR tenant_id = public.app_current_tenant());

-- ---- Existing chamas: a default Deposits product and an account for every seated member -------

INSERT INTO account.savings_products (id, tenant_id, code, name, kind, ledger_account_code, contribution_minor,
                                      allocation_priority, mandatory, counts_toward_loan_limit, refundable_on_exit,
                                      earns_dividend, is_default, active, created_at, updated_at)
SELECT gen_random_uuid(), c.id, 'DEP', 'Deposits', 'DEPOSITS', a.code, c.contribution_amount_minor,
       50, true, true, true, true, true, true, now(), now()
  FROM chama.chamas c
  JOIN ledger.accounts a ON a.tenant_id = c.id AND a.system_key = 'MEMBER_SAVINGS';

INSERT INTO account.member_accounts (id, tenant_id, account_number, member_user_id, product_id, status, opened_on,
                                     created_at)
SELECT gen_random_uuid(), m.tenant_id,
       'DEP-' || coalesce(lpad(m.member_number::text, 4, '0'), left(m.user_id::text, 8)),
       m.user_id, p.id, 'ACTIVE',
       coalesce((m.joined_at AT TIME ZONE 'Africa/Nairobi')::date, current_date), now()
  FROM member.memberships m
  JOIN account.savings_products p ON p.tenant_id = m.tenant_id AND p.is_default
 WHERE m.status IN ('ACTIVE', 'SUSPENDED')
   AND m.user_id IS NOT NULL;

-- ---- Contributions per product -------------------------------------------------------------------

-- What each product asked for in a period, fixed when the period opened.
CREATE TABLE contribution.period_products
(
    tenant_id    UUID   NOT NULL DEFAULT public.app_current_tenant(),
    period_id    UUID   NOT NULL,
    product_id   UUID   NOT NULL,
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    PRIMARY KEY (period_id, product_id),
    FOREIGN KEY (tenant_id, period_id) REFERENCES contribution.periods (tenant_id, id)
);

INSERT INTO contribution.period_products (tenant_id, period_id, product_id, amount_minor)
SELECT p.tenant_id, p.id, sp.id, p.amount_due_minor
  FROM contribution.periods p
  JOIN account.savings_products sp ON sp.tenant_id = p.tenant_id AND sp.is_default;

-- A due is now per member, period AND product.
ALTER TABLE contribution.dues ADD COLUMN product_id UUID;
UPDATE contribution.dues d
   SET product_id = sp.id
  FROM account.savings_products sp
 WHERE sp.tenant_id = d.tenant_id AND sp.is_default;
ALTER TABLE contribution.dues ALTER COLUMN product_id SET NOT NULL;
ALTER TABLE contribution.dues DROP CONSTRAINT dues_period_id_member_user_id_key;
ALTER TABLE contribution.dues ADD CONSTRAINT dues_period_member_product_key UNIQUE (period_id, member_user_id, product_id);

-- How each payment was split across the member's products. Never changed: a reversed payment's
-- allocations simply stop counting.
CREATE TABLE contribution.payment_allocations
(
    tenant_id    UUID   NOT NULL DEFAULT public.app_current_tenant(),
    payment_id   UUID   NOT NULL REFERENCES contribution.payments (id),
    product_id   UUID   NOT NULL,
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    PRIMARY KEY (payment_id, product_id)
);
CREATE INDEX idx_payment_allocations_tenant ON contribution.payment_allocations (tenant_id, product_id);

INSERT INTO contribution.payment_allocations (tenant_id, payment_id, product_id, amount_minor)
SELECT p.tenant_id, p.id, sp.id, p.amount_minor
  FROM contribution.payments p
  JOIN account.savings_products sp ON sp.tenant_id = p.tenant_id AND sp.is_default;

GRANT SELECT, INSERT ON contribution.period_products, contribution.payment_allocations TO chama_app;

ALTER TABLE contribution.period_products ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON contribution.period_products
    USING (public.app_rls_bypass() OR tenant_id = public.app_current_tenant())
    WITH CHECK (public.app_rls_bypass() OR tenant_id = public.app_current_tenant());

ALTER TABLE contribution.payment_allocations ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON contribution.payment_allocations
    USING (public.app_rls_bypass() OR tenant_id = public.app_current_tenant())
    WITH CHECK (public.app_rls_bypass() OR tenant_id = public.app_current_tenant());

-- ---- Loan products ------------------------------------------------------------------------------

CREATE TABLE loan.products
(
    id                        UUID PRIMARY KEY,
    tenant_id                 UUID        NOT NULL DEFAULT public.app_current_tenant(),
    code                      VARCHAR(10) NOT NULL CHECK (code ~ '^[A-Z][A-Z0-9]{1,9}$'),
    name                      VARCHAR(60) NOT NULL,
    interest_bp               INT         NOT NULL CHECK (interest_bp BETWEEN 0 AND 10000),
    max_term_months           INT         NOT NULL CHECK (max_term_months BETWEEN 1 AND 36),
    max_multiplier            INT         NOT NULL CHECK (max_multiplier BETWEEN 1 AND 10),
    min_membership_months     INT         NOT NULL CHECK (min_membership_months BETWEEN 0 AND 60),
    guarantor_threshold_minor BIGINT      NOT NULL CHECK (guarantor_threshold_minor >= 0),
    guarantors_required       INT         NOT NULL CHECK (guarantors_required BETWEEN 0 AND 5),
    -- The default product's terms are the chama's loan settings, kept in step when they change.
    is_default                BOOLEAN     NOT NULL DEFAULT false,
    active                    BOOLEAN     NOT NULL DEFAULT true,
    created_by                UUID,
    created_at                TIMESTAMPTZ NOT NULL,
    updated_at                TIMESTAMPTZ NOT NULL,
    version                   BIGINT      NOT NULL DEFAULT 0,
    CHECK (NOT is_default OR active),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, code)
);
CREATE UNIQUE INDEX ux_loan_products_default ON loan.products (tenant_id) WHERE is_default;

INSERT INTO loan.products (id, tenant_id, code, name, interest_bp, max_term_months, max_multiplier,
                           min_membership_months, guarantor_threshold_minor, guarantors_required, is_default, active,
                           created_at, updated_at)
SELECT gen_random_uuid(), c.id, 'NORMAL', 'Normal loan', c.loan_interest_bp, 36, c.loan_max_multiplier,
       c.loan_min_membership_months, c.loan_guarantor_threshold_minor, c.loan_guarantors_required, true, true,
       now(), now()
  FROM chama.chamas c;

ALTER TABLE loan.loans ADD COLUMN product_id UUID;
UPDATE loan.loans l
   SET product_id = p.id
  FROM loan.products p
 WHERE p.tenant_id = l.tenant_id AND p.is_default;
ALTER TABLE loan.loans ALTER COLUMN product_id SET NOT NULL;
ALTER TABLE loan.loans ADD FOREIGN KEY (tenant_id, product_id) REFERENCES loan.products (tenant_id, id);

-- One loan in progress per member PER PRODUCT: a member may hold a normal loan and an emergency loan.
DROP INDEX loan.ux_loans_one_open;
CREATE UNIQUE INDEX ux_loans_one_open_per_product ON loan.loans (tenant_id, borrower_user_id, product_id)
    WHERE status IN ('PENDING_GUARANTORS', 'PENDING_APPROVAL', 'APPROVED', 'ACTIVE');

GRANT SELECT, INSERT, UPDATE ON loan.products TO chama_app;

ALTER TABLE loan.products ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON loan.products
    USING (public.app_rls_bypass() OR tenant_id = public.app_current_tenant())
    WITH CHECK (public.app_rls_bypass() OR tenant_id = public.app_current_tenant());

-- ---- Permissions --------------------------------------------------------------------------------

-- The new permissions go to the roles that get them by default (DefaultRolePermissions).
INSERT INTO member.role_permissions (tenant_id, role, permission)
SELECT c.id, r.role, r.permission
  FROM chama.chamas c
 CROSS JOIN (VALUES ('CHAIRPERSON', 'PRODUCTS_MANAGE'),
                    ('CHAIRPERSON', 'MEMBER_ACCOUNTS_VIEW_ALL'),
                    ('CHAIRPERSON', 'MEMBER_ACCOUNTS_MANAGE'),
                    ('TREASURER', 'PRODUCTS_MANAGE'),
                    ('TREASURER', 'MEMBER_ACCOUNTS_VIEW_ALL'),
                    ('TREASURER', 'MEMBER_ACCOUNTS_MANAGE'),
                    ('SECRETARY', 'MEMBER_ACCOUNTS_VIEW_ALL'),
                    ('AUDITOR', 'MEMBER_ACCOUNTS_VIEW_ALL')) AS r (role, permission)
ON CONFLICT DO NOTHING;
