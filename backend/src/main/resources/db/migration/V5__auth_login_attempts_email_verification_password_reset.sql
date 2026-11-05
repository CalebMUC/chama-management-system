-- ============================================================
-- V5 — Auth: login attempt history + lockout, email verification,
--      password reset / change
-- ============================================================

ALTER TABLE auth.users
    ADD COLUMN email_verified_at   TIMESTAMPTZ,
    ADD COLUMN password_changed_at TIMESTAMPTZ;

-- Sessions are ended when the password changes.
ALTER TABLE auth.refresh_tokens DROP CONSTRAINT refresh_tokens_revoked_reason_check;
ALTER TABLE auth.refresh_tokens ADD CONSTRAINT refresh_tokens_revoked_reason_check
    CHECK (revoked_reason IN ('ROTATED', 'LOGOUT', 'LOGOUT_ALL', 'REUSE_DETECTED', 'PASSWORD_CHANGED'));

-- Every login attempt, successful or not: drives the lockout and is the
-- security trail ("who tried to log in as me, from where?").
-- Keyed by email rather than user so unknown emails are throttled exactly
-- like real ones and the lockout cannot be used to discover accounts.
CREATE TABLE auth.login_attempts
(
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email        VARCHAR(255) NOT NULL,
    user_id      UUID REFERENCES auth.users (id) ON DELETE SET NULL,
    outcome      VARCHAR(30)  NOT NULL
        CHECK (outcome IN ('SUCCESS', 'BAD_CREDENTIALS', 'ACCOUNT_LOCKED', 'ACCOUNT_DISABLED', 'EMAIL_NOT_VERIFIED')),
    ip_address   VARCHAR(45),
    user_agent   VARCHAR(512),
    attempted_at TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_login_attempts_email_time ON auth.login_attempts (email, attempted_at DESC);
CREATE INDEX idx_login_attempts_attempted_at ON auth.login_attempts (attempted_at);

-- Single-use links sent by email. Only the SHA-256 hash is stored.
CREATE TABLE auth.one_time_tokens
(
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID        NOT NULL REFERENCES auth.users (id) ON DELETE CASCADE,
    purpose        VARCHAR(30) NOT NULL CHECK (purpose IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET')),
    token_hash     VARCHAR(64) NOT NULL UNIQUE,
    created_at     TIMESTAMPTZ NOT NULL,
    expires_at     TIMESTAMPTZ NOT NULL,
    used_at        TIMESTAMPTZ,
    invalidated_at TIMESTAMPTZ
);
CREATE INDEX idx_one_time_tokens_user_purpose ON auth.one_time_tokens (user_id, purpose, created_at DESC);
