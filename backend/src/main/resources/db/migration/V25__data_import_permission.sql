-- ============================================================
-- V25 — Member: DATA_IMPORT permission
-- Importing opening balances and history from Excel moves out of CHAMA_SETTINGS_MANAGE into its own
-- permission, so the treasurer can import without being able to change the chama's settings.
-- Every role that could import before keeps that ability (chamas that changed their mapping keep it);
-- the treasurer gains it in every chama. Keep in step with DefaultRolePermissions, which seeds new chamas.
-- ============================================================

INSERT INTO member.role_permissions (tenant_id, role, permission)
SELECT rp.tenant_id, rp.role, 'DATA_IMPORT'
  FROM member.role_permissions rp
 WHERE rp.permission = 'CHAMA_SETTINGS_MANAGE'
UNION
SELECT c.id, 'TREASURER', 'DATA_IMPORT'
  FROM chama.chamas c
ON CONFLICT DO NOTHING;
