package ke.chama.modules.auth.domain.exception;

import ke.chama.shared.domain.exception.ErrorType;

public class InvalidTokenException extends AuthException {

    public InvalidTokenException(String message) {
        super("AUTH_INVALID_TOKEN", ErrorType.UNAUTHORIZED, message);
    }
}
