-- Multi-tenancy: every chama is a tenant sharing this database.
--
-- * Every tenant-owned row carries tenant_id (the chama's id). Users belong to exactly one chama;
--   platform administrators belong to the platform tenant 00000000-0000-0000-0000-000000000001.
-- * Each chama has a short unique code (used at login) and a licence.
-- * Row-level security (RLS) makes PostgreSQL itself refuse rows of other tenants. The
--   application connects as the owner but switches every session to the restricted role
--   chama_app, and sets app.tenant_id for the current request (see TenantAwareDataSource).
--   Superusers and table owners bypass RLS, so the application must never query as them.
--
-- NOTE: this migration DELETES all existing users, chamas, memberships and audit entries.
-- They predate tenancy (a user could belong to several chamas), cannot be mapped to a single
-- tenant, and only development data exists at this point.

-- ---- 1. Reset pre-tenancy data ----------------------------------------------------------------

DELETE FROM auth.one_time_tokens;
DELETE FROM auth.refresh_tokens;
DELETE FROM auth.login_attempts;
DELETE FROM auth.user_roles;
DELETE FROM auth.users;
DELETE FROM member.membership_roles;
DELETE FROM member.memberships;
DELETE FROM member.role_permissions;
DELETE FROM chama.chamas;
DELETE FROM public.event_publication;

-- The audit log is append-only; lift its guard for this one reset, then restore it.
ALTER TABLE audit.audit_log DISABLE TRIGGER audit_log_no_update_delete;
ALTER TABLE audit.audit_log DISABLE TRIGGER audit_log_no_truncate;
TRUNCATE audit.audit_log;
ALTER TABLE audit.audit_log ENABLE TRIGGER audit_log_no_update_delete;
ALTER TABLE audit.audit_log ENABLE TRIGGER audit_log_no_truncate;

-- ---- 2. Session helpers used by the policies ------------------------------------------------

-- The tenant of the current database session, or NULL when none is set.
CREATE FUNCTION public.app_current_tenant() RETURNS uuid
    LANGUAGE sql STABLE AS
$$ SELECT NULLIF(current_setting('app.tenant_id', true), '')::uuid $$;

-- True only inside TenantContext.callAsSystem (platform administration, housekeeping jobs).
CREATE FUNCTION public.app_rls_bypass() RETURNS boolean
    LANGUAGE sql STABLE AS
$$ SELECT coalesce(current_setting('app.bypass_rls', true), '') = 'on' $$;

-- ---- 3. auth: users and their credentials belong to one tenant ------------------------------

ALTER TABLE auth.users ADD COLUMN tenant_id UUID NOT NULL DEFAULT public.app_current_tenant();
ALTER TABLE auth.users DROP CONSTRAINT IF EXISTS users_email_key;
DROP INDEX IF EXISTS auth.ux_users_email_lower;
-- The same email may register in different chamas, as separate accounts.
CREATE UNIQUE INDEX ux_users_tenant_email ON auth.users (tenant_id, lower(email));

ALTER TABLE auth.refresh_tokens ADD COLUMN tenant_id UUID NOT NULL DEFAULT public.app_current_tenant();
CREATE INDEX idx_refresh_tokens_tenant ON auth.refresh_tokens (tenant_id);

ALTER TABLE auth.one_time_tokens ADD COLUMN tenant_id UUID NOT NULL DEFAULT public.app_current_tenant();
CREATE INDEX idx_one_time_tokens_tenant ON auth.one_time_tokens (tenant_id);

ALTER TABLE auth.login_attempts ADD COLUMN tenant_id UUID NOT NULL DEFAULT public.app_current_tenant();
DROP INDEX IF EXISTS auth.idx_login_attempts_email_time;
CREATE INDEX idx_login_attempts_tenant_email_time ON auth.login_attempts (tenant_id, email, attempted_at DESC);

-- ---- 4. chama: the tenant registry and licences -----------------------------------------------

-- Lower-case letters, digits and single hyphens; 3 to 30 characters, e.g. "mokica", "jamii-sacco".
ALTER TABLE chama.chamas ADD COLUMN code VARCHAR(30) NOT NULL
    CHECK (code ~ '^[a-z0-9]+(-[a-z0-9]+)*$' AND length(code) BETWEEN 3 AND 30);
CREATE UNIQUE INDEX ux_chamas_code ON chama.chamas (code);

CREATE TABLE chama.licenses
(
    id            UUID PRIMARY KEY,
    tenant_id     UUID         NOT NULL UNIQUE DEFAULT public.app_current_tenant(),
    license_key   VARCHAR(40)  NOT NULL UNIQUE,
    plan          VARCHAR(20)  NOT NULL CHECK (plan IN ('TRIAL', 'BASIC', 'STANDARD', 'PREMIUM')),
    status        VARCHAR(20)  NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    valid_from    TIMESTAMPTZ  NOT NULL,
    valid_until   TIMESTAMPTZ  NOT NULL,
    max_members   INTEGER      NOT NULL CHECK (max_members > 0),
    notes         VARCHAR(500),
    updated_by    UUID,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    CHECK (valid_until > valid_from)
);

CREATE TABLE chama.license_features
(
    license_id UUID        NOT NULL REFERENCES chama.licenses (id) ON DELETE CASCADE,
    feature    VARCHAR(40) NOT NULL,
    PRIMARY KEY (license_id, feature)
);

-- ---- 5. member: one membership per user, created when they register --------------------------

ALTER TABLE member.memberships RENAME COLUMN chama_id TO tenant_id;
ALTER TABLE member.memberships RENAME COLUMN invited_email TO email;
ALTER TABLE member.memberships RENAME COLUMN invited_at TO applied_at;
ALTER TABLE member.memberships DROP COLUMN invited_by;
ALTER TABLE member.memberships DROP COLUMN responded_at;
ALTER TABLE member.memberships ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE member.memberships ALTER COLUMN tenant_id SET DEFAULT public.app_current_tenant();
ALTER TABLE member.memberships DROP CONSTRAINT memberships_status_check;
ALTER TABLE member.memberships ADD CONSTRAINT memberships_status_check
    CHECK (status IN ('PENDING_APPROVAL', 'REJECTED', 'ACTIVE', 'SUSPENDED', 'EXITED'));

DROP INDEX member.ux_memberships_open_email;
DROP INDEX member.ux_memberships_open_user;
DROP INDEX member.ux_memberships_member_number;
DROP INDEX member.idx_memberships_user;
DROP INDEX member.idx_memberships_invited_email;
CREATE UNIQUE INDEX ux_memberships_user ON member.memberships (user_id);
CREATE UNIQUE INDEX ux_memberships_member_number ON member.memberships (tenant_id, member_number)
    WHERE member_number IS NOT NULL;
CREATE INDEX idx_memberships_tenant_status ON member.memberships (tenant_id, status);

ALTER TABLE member.role_permissions RENAME COLUMN chama_id TO tenant_id;
ALTER TABLE member.role_permissions ALTER COLUMN tenant_id SET DEFAULT public.app_current_tenant();

-- ---- 6. audit: every entry belongs to a tenant ----------------------------------------------

ALTER TABLE audit.audit_log RENAME COLUMN chama_id TO tenant_id;
ALTER TABLE audit.audit_log ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE audit.audit_log ALTER COLUMN tenant_id SET DEFAULT public.app_current_tenant();
ALTER INDEX audit.idx_audit_log_chama_time RENAME TO idx_audit_log_tenant_time;

-- ---- 7. The application role ------------------------------------------------------------------

DO
$$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'chama_app') THEN
        CREATE ROLE chama_app NOLOGIN NOSUPERUSER NOBYPASSRLS;
    END IF;
END
$$;
-- Lets the connecting user switch to chama_app (a superuser can anyway).
GRANT chama_app TO CURRENT_USER;

GRANT USAGE ON SCHEMA auth, chama, member, audit, public TO chama_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA auth, chama, member TO chama_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.event_publication TO chama_app;
-- The audit log can be read and appended to, never changed: no UPDATE, DELETE or TRUNCATE.
GRANT SELECT, INSERT ON audit.audit_log TO chama_app;

-- Tables later migrations add to these schemas are usable by the application too. New schemas
-- (one per future module) must grant USAGE and table privileges to chama_app themselves.
ALTER DEFAULT PRIVILEGES IN SCHEMA auth, chama, member
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO chama_app;

-- ---- 8. Row-level security --------------------------------------------------------------------

-- A row is visible and writable only for its own tenant (or in system mode).
ALTER TABLE auth.users ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON auth.users
    USING (public.app_rls_bypass() OR tenant_id = public.app_current_tenant())
    WITH CHECK (public.app_rls_bypass() OR tenant_id = public.app_current_tenant());

ALTER TABLE auth.refresh_tokens ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON auth.refresh_tokens
    USING (public.app_rls_bypass() OR tenant_id = public.app_current_tenant())
    WITH CHECK (public.app_rls_bypass() OR tenant_id = public.app_current_tenant());

ALTER TABLE auth.one_time_tokens ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON auth.one_time_tokens
    USING (public.app_rls_bypass() OR tenant_id = public.app_current_tenant())
    WITH CHECK (public.app_rls_bypass() OR tenant_id = public.app_current_tenant());

ALTER TABLE auth.login_attempts ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON auth.login_attempts
    USING (public.app_rls_bypass() OR tenant_id = public.app_current_tenant())
    WITH CHECK (public.app_rls_bypass() OR tenant_id = public.app_current_tenant());

-- A chama's own row: its id is the tenant id.
ALTER TABLE chama.chamas ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON chama.chamas
    USING (public.app_rls_bypass() OR id = public.app_current_tenant())
    WITH CHECK (public.app_rls_bypass() OR id = public.app_current_tenant());

ALTER TABLE chama.licenses ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON chama.licenses
    USING (public.app_rls_bypass() OR tenant_id = public.app_current_tenant())
    WITH CHECK (public.app_rls_bypass() OR tenant_id = public.app_current_tenant());

ALTER TABLE member.memberships ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON member.memberships
    USING (public.app_rls_bypass() OR tenant_id = public.app_current_tenant())
    WITH CHECK (public.app_rls_bypass() OR tenant_id = public.app_current_tenant());

ALTER TABLE member.role_permissions ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON member.role_permissions
    USING (public.app_rls_bypass() OR tenant_id = public.app_current_tenant())
    WITH CHECK (public.app_rls_bypass() OR tenant_id = public.app_current_tenant());

ALTER TABLE audit.audit_log ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON audit.audit_log
    USING (public.app_rls_bypass() OR tenant_id = public.app_current_tenant())
    WITH CHECK (public.app_rls_bypass() OR tenant_id = public.app_current_tenant());

-- Child tables without tenant_id (auth.user_roles, member.membership_roles, chama.license_features)
-- are only reached through their tenant-owned parent and hold no identifying data.
