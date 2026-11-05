package ke.chama.modules.auth.domain.exception;

import ke.chama.shared.domain.exception.ErrorType;

/**
 * For lookups by an authenticated caller only. Login must answer a wrong email and a wrong
 * password with the same generic "invalid credentials" error, so it cannot be used to find
 * which emails are registered.
 */
public class UserNotFoundException extends AuthException {

    public UserNotFoundException(String identifier) {
        super("AUTH_USER_NOT_FOUND", ErrorType.NOT_FOUND, "User not found: " + identifier);
    }
}
