-- ============================================================
-- V7 — Chama module: chamas and their rules
-- Amounts in minor units (KES cents), rates in basis points (100 = 1%).
-- ============================================================

CREATE SCHEMA IF NOT EXISTS chama;

CREATE TABLE chama.chamas
(
    id                             UUID PRIMARY KEY,
    name                           VARCHAR(120) NOT NULL,
    description                    VARCHAR(1000),
    currency                       VARCHAR(3)   NOT NULL,
    status                         VARCHAR(20)  NOT NULL CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    created_by                     UUID         NOT NULL,

    contribution_amount_minor      BIGINT       NOT NULL CHECK (contribution_amount_minor > 0),
    contribution_frequency         VARCHAR(20)  NOT NULL CHECK (contribution_frequency IN ('MONTHLY', 'WEEKLY')),
    contribution_due_day           INTEGER      NOT NULL,
    late_grace_days                INTEGER      NOT NULL,
    late_penalty_bp                INTEGER      NOT NULL CHECK (late_penalty_bp BETWEEN 0 AND 10000),
    loan_max_multiplier            INTEGER      NOT NULL,
    loan_interest_bp               INTEGER      NOT NULL CHECK (loan_interest_bp BETWEEN 0 AND 10000),
    loan_min_membership_months     INTEGER      NOT NULL,
    loan_guarantor_threshold_minor BIGINT       NOT NULL,
    loan_guarantors_required       INTEGER      NOT NULL,
    approval_quorum                INTEGER      NOT NULL CHECK (approval_quorum >= 1),

    created_at                     TIMESTAMPTZ  NOT NULL,
    updated_at                     TIMESTAMPTZ  NOT NULL,
    version                        BIGINT       NOT NULL DEFAULT 0
);
