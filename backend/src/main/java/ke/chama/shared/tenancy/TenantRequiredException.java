package ke.chama.shared.tenancy;

import ke.chama.shared.domain.exception.DomainException;
import ke.chama.shared.domain.exception.ErrorType;

/** A chama-level operation was called without saying which chama. */
public class TenantRequiredException extends DomainException {

    public TenantRequiredException() {
        super("TENANT_REQUIRED", ErrorType.VALIDATION,
                "Say which chama this is for: send the " + TenantContext.HEADER + " header");
    }
}
