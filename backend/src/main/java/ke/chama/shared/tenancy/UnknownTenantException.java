package ke.chama.shared.tenancy;

import ke.chama.shared.domain.exception.DomainException;
import ke.chama.shared.domain.exception.ErrorType;

/** The {@value TenantContext#HEADER} header named a chama that does not exist. */
public class UnknownTenantException extends DomainException {

    public UnknownTenantException(String code) {
        super("TENANT_NOT_FOUND", ErrorType.NOT_FOUND, "No chama is registered with the code '" + code + "'");
    }
}
