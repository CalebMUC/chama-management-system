-- ============================================================
-- V8 — Member module: memberships, roles, per-chama role permissions
-- chama_id and user_id reference other modules' data, so there are
-- deliberately no foreign keys to chama.chamas or auth.users (ADR-006).
-- ============================================================

CREATE SCHEMA IF NOT EXISTS member;

CREATE TABLE member.memberships
(
    id                UUID PRIMARY KEY,
    chama_id          UUID         NOT NULL,
    user_id           UUID,
    invited_email     VARCHAR(255) NOT NULL,
    member_number     INTEGER,
    status            VARCHAR(20)  NOT NULL CHECK (status IN
        ('INVITED', 'DECLINED', 'PENDING_APPROVAL', 'REJECTED', 'ACTIVE', 'SUSPENDED', 'EXITED')),
    invited_by        UUID,
    invited_at        TIMESTAMPTZ  NOT NULL,
    responded_at      TIMESTAMPTZ,
    decided_by        UUID,
    decided_at        TIMESTAMPTZ,
    joined_at         TIMESTAMPTZ,
    suspended_by      UUID,
    suspended_at      TIMESTAMPTZ,
    suspension_reason VARCHAR(500),
    created_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    version           BIGINT       NOT NULL DEFAULT 0
);

-- One open membership per person per chama; history (declined, rejected, exited) may repeat.
CREATE UNIQUE INDEX ux_memberships_open_email ON member.memberships (chama_id, invited_email)
    WHERE status IN ('INVITED', 'PENDING_APPROVAL', 'ACTIVE', 'SUSPENDED');
CREATE UNIQUE INDEX ux_memberships_open_user ON member.memberships (chama_id, user_id)
    WHERE user_id IS NOT NULL AND status IN ('INVITED', 'PENDING_APPROVAL', 'ACTIVE', 'SUSPENDED');
CREATE UNIQUE INDEX ux_memberships_member_number ON member.memberships (chama_id, member_number)
    WHERE member_number IS NOT NULL;
CREATE INDEX idx_memberships_user ON member.memberships (user_id);
CREATE INDEX idx_memberships_invited_email ON member.memberships (invited_email, status);

CREATE TABLE member.membership_roles
(
    membership_id UUID        NOT NULL REFERENCES member.memberships (id) ON DELETE CASCADE,
    role          VARCHAR(30) NOT NULL CHECK (role IN ('MEMBER', 'TREASURER', 'SECRETARY', 'CHAIRPERSON', 'AUDITOR')),
    PRIMARY KEY (membership_id, role)
);

-- What each role may do in each chama. Seeded with defaults when a chama is created;
-- editable by whoever holds ROLE_PERMISSIONS_MANAGE.
CREATE TABLE member.role_permissions
(
    chama_id   UUID        NOT NULL,
    role       VARCHAR(30) NOT NULL CHECK (role IN ('MEMBER', 'TREASURER', 'SECRETARY', 'CHAIRPERSON', 'AUDITOR')),
    permission VARCHAR(60) NOT NULL,
    PRIMARY KEY (chama_id, role, permission)
);
