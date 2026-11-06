/**
 * Append-only audit log: who did what, in which chama, when and from where.
 * Other modules write through {@code audit.api.AuditTrail}; officials with AUDIT_LOG_VIEW read it.
 * The table rejects UPDATE, DELETE and TRUNCATE at the database level.
 */
@ApplicationModule(id = "audit", displayName = "Audit")
package ke.chama.modules.audit;

import org.springframework.modulith.ApplicationModule;
