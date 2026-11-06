-- ============================================================
-- V4 — Auth: hashed + auditable refresh tokens, platform-only roles
-- ============================================================

-- 1. Refresh tokens are stored as SHA-256 hashes. Existing rows hold raw
--    tokens that cannot be converted, so those sessions are ended (users log in again).
DELETE FROM auth.refresh_tokens;

ALTER TABLE auth.refresh_tokens RENAME COLUMN token TO token_hash;
ALTER TABLE auth.refresh_tokens
    ALTER COLUMN token_hash TYPE VARCHAR(64),
    ADD COLUMN revoked_at     TIMESTAMPTZ,
    ADD COLUMN revoked_reason VARCHAR(30)
        CHECK (revoked_reason IN ('ROTATED', 'LOGOUT', 'LOGOUT_ALL', 'REUSE_DETECTED'));

-- 2. Chama roles (Treasurer, Secretary, Chairman, Member) are per chama and move to the
--    member module's memberships (Phase 2). auth.roles keeps only platform roles.
DELETE FROM auth.roles WHERE name IN ('ROLE_MEMBER', 'ROLE_TREASURER', 'ROLE_SECRETARY', 'ROLE_CHAIRMAN');

INSERT INTO auth.roles (name) VALUES ('ROLE_USER') ON CONFLICT (name) DO NOTHING;

INSERT INTO auth.user_roles (user_id, role_id)
SELECT u.id, r.id
  FROM auth.users u
  CROSS JOIN auth.roles r
 WHERE r.name = 'ROLE_USER'
ON CONFLICT DO NOTHING;

-- 3. Defence in depth: the application lower-cases emails; the database refuses
--    case-variant duplicates even if some future code path forgets.
CREATE UNIQUE INDEX IF NOT EXISTS ux_users_email_lower ON auth.users (lower(email));
