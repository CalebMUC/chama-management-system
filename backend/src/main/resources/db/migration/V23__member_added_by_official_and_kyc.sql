-- ============================================================
-- V23 — Members added by an official, with KYC details
-- An official (MEMBER_INVITE) can open a member's account directly: the member is ACTIVE at once,
-- signs in with a temporary password the official set, and must choose their own at first sign-in.
-- ============================================================

-- 1. Accounts that must change their password before doing anything else.
ALTER TABLE auth.users ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;

-- 2. Know-your-customer details of a member, kept by the chama's officials. One row per membership.
CREATE TABLE member.member_kyc
(
    membership_id    UUID PRIMARY KEY REFERENCES member.memberships (id),
    tenant_id        UUID         NOT NULL DEFAULT public.app_current_tenant(),
    id_type          VARCHAR(20)  NOT NULL CHECK (id_type IN ('NATIONAL_ID', 'PASSPORT', 'ALIEN_ID')),
    -- Stored trimmed and upper-cased.
    id_number        VARCHAR(30)  NOT NULL,
    date_of_birth    DATE         NOT NULL,
    gender           VARCHAR(10)  NOT NULL CHECK (gender IN ('MALE', 'FEMALE', 'OTHER')),
    county           VARCHAR(60)  NOT NULL,
    town             VARCHAR(100),
    physical_address VARCHAR(300),
    updated_by       UUID         NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL
);
-- One identity document belongs to one member of a chama.
CREATE UNIQUE INDEX ux_member_kyc_id_document ON member.member_kyc (tenant_id, id_type, id_number);

-- Corrected, never deleted.
GRANT SELECT, INSERT, UPDATE ON member.member_kyc TO chama_app;

ALTER TABLE member.member_kyc ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON member.member_kyc
    USING (public.app_rls_bypass() OR tenant_id = public.app_current_tenant())
    WITH CHECK (public.app_rls_bypass() OR tenant_id = public.app_current_tenant());
