package ke.chama.modules.auth.domain.exception;

import ke.chama.shared.domain.exception.ErrorType;

/** Only raised after the password is verified, so it does not reveal which accounts exist. */
public class AccountDisabledException extends AuthException {

    public AccountDisabledException() {
        super("AUTH_ACCOUNT_DISABLED", ErrorType.FORBIDDEN, "This account has been disabled");
    }
}
