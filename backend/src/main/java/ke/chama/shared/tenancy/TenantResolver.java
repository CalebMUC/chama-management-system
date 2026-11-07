package ke.chama.shared.tenancy;

import java.util.Optional;
import java.util.UUID;

/**
 * Looks up a chama by its code. Implemented by the chama module, which owns the tenant registry;
 * declared here so the request filter does not depend on that module.
 */
public interface TenantResolver {

    /** {@code code} is matched case-insensitively. */
    Optional<UUID> findIdByCode(String code);
}
