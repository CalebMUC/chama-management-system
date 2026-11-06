package ke.chama.modules.auth.domain.exception;

import ke.chama.shared.domain.exception.DomainException;
import ke.chama.shared.domain.exception.ErrorType;

/** Base for auth-module failures. Codes are prefixed {@code AUTH_}. */
public abstract class AuthException extends DomainException {

    protected AuthException(String code, ErrorType type, String message) {
        super(code, type, message);
    }
}
