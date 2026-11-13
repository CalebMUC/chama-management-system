-- ============================================================
-- V22 — Member: ADMIN chama role
-- Runs the chama's operations (members, roles, settings, products) and handles no money.
-- Keep the permissions in step with DefaultRolePermissions.ADMIN, which seeds new chamas.
-- ============================================================

-- 1. Allow the new role (the V8 CHECKs carry Postgres's default names).
ALTER TABLE member.membership_roles DROP CONSTRAINT membership_roles_role_check;
ALTER TABLE member.membership_roles ADD CONSTRAINT membership_roles_role_check
    CHECK (role IN ('MEMBER', 'TREASURER', 'SECRETARY', 'CHAIRPERSON', 'AUDITOR', 'ADMIN'));

ALTER TABLE member.role_permissions DROP CONSTRAINT role_permissions_role_check;
ALTER TABLE member.role_permissions ADD CONSTRAINT role_permissions_role_check
    CHECK (role IN ('MEMBER', 'TREASURER', 'SECRETARY', 'CHAIRPERSON', 'AUDITOR', 'ADMIN'));

-- 2. Give the role its default permissions in every existing chama.
INSERT INTO member.role_permissions (tenant_id, role, permission)
SELECT c.id, 'ADMIN', p.permission
  FROM chama.chamas c
 CROSS JOIN (VALUES ('CHAMA_VIEW'),
                    ('LOAN_APPLY'),
                    ('EXPENSE_VIEW'),
                    ('INVESTMENT_VIEW'),
                    ('PAYMENT_SUBMIT'),
                    ('CHAMA_SETTINGS_MANAGE'),
                    ('MEMBER_VIEW'),
                    ('MEMBER_INVITE'),
                    ('MEMBER_APPROVE'),
                    ('MEMBER_SUSPEND'),
                    ('MEMBER_ROLES_MANAGE'),
                    ('CONTRIBUTION_VIEW_ALL'),
                    ('LOAN_VIEW_ALL'),
                    ('PENALTY_VIEW_ALL'),
                    ('REPORT_VIEW_ALL'),
                    ('AUDIT_LOG_VIEW'),
                    ('PRODUCTS_MANAGE'),
                    ('MEMBER_ACCOUNTS_VIEW_ALL'),
                    ('MEMBER_ACCOUNTS_MANAGE')) AS p (permission)
ON CONFLICT DO NOTHING;
