-- ============================================================
-- V2 — Auth module: move to its own schema and align with the entities
--
-- One schema per module keeps data ownership explicit and makes a
-- module extractable later (ADR-006). No foreign keys cross schemas.
-- ============================================================

CREATE SCHEMA IF NOT EXISTS auth;

ALTER TABLE public.users          SET SCHEMA auth;
ALTER TABLE public.roles          SET SCHEMA auth;
ALTER TABLE public.user_roles     SET SCHEMA auth;
ALTER TABLE public.refresh_tokens SET SCHEMA auth;

-- Phone is required (M-Pesa + SMS). Fails if existing rows have no phone:
-- backfill them first.
ALTER TABLE auth.users
    ALTER COLUMN phone_number SET NOT NULL,
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'UTC',
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING updated_at AT TIME ZONE 'UTC',
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE auth.refresh_tokens
    ALTER COLUMN expiry_date TYPE TIMESTAMPTZ USING expiry_date AT TIME ZONE 'UTC',
    ALTER COLUMN created_at  TYPE TIMESTAMPTZ USING created_at  AT TIME ZONE 'UTC';

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user_id ON auth.refresh_tokens (user_id);
