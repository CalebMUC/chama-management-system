package ke.chama.modules.auth.domain.exception;

import ke.chama.shared.domain.exception.ErrorType;

public class IncorrectCurrentPasswordException extends AuthException {

    public IncorrectCurrentPasswordException() {
        super("AUTH_CURRENT_PASSWORD_INCORRECT", ErrorType.VALIDATION, "The current password is incorrect");
    }
}
