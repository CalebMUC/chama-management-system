package ke.chama.shared.tenancy;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;

import java.util.UUID;

/**
 * Tells Hibernate the tenant of each new session. Entities with a {@code @TenantId} field get
 * that field set on insert, and every query on them is filtered by it. In system mode the
 * session is "root" and sees all tenants.
 */
public class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<UUID> {

    @Override
    public UUID resolveCurrentTenantIdentifier() {
        return TenantContext.isSystem() ? TenantContext.SYSTEM : TenantContext.databaseTenant();
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }

    @Override
    public boolean isRoot(UUID tenantId) {
        return TenantContext.SYSTEM.equals(tenantId);
    }
}
