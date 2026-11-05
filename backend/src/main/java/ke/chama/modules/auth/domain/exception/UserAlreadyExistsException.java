package ke.chama.modules.auth.domain.exception;

import ke.chama.shared.domain.exception.ErrorType;

public class UserAlreadyExistsException extends AuthException {

    public UserAlreadyExistsException(String email) {
        super("AUTH_USER_ALREADY_EXISTS", ErrorType.CONFLICT, "A user with email " + email + " already exists");
    }
}
