package ke.chama.modules.auth.domain.exception;

import ke.chama.shared.domain.exception.ErrorType;

/**
 * Wrong email or wrong password. Both cases deliberately get the same message, so login
 * cannot be used to find which emails are registered.
 */
public class InvalidCredentialsException extends AuthException {

    public InvalidCredentialsException() {
        super("AUTH_INVALID_CREDENTIALS", ErrorType.UNAUTHORIZED, "Invalid email or password");
    }
}
