package ke.chama.modules.audit.api;

/**
 * Records accountable actions. Call it inside the same transaction as the change: if the change
 * rolls back, so does its audit entry, and a committed change always has one.
 */
public interface AuditTrail {

    void record(AuditEntry entry);
}
