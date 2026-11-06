package ke.chama.modules.auth.domain.exception;

import ke.chama.shared.domain.exception.ErrorType;

/** Only raised when {@code chama.auth.require-verified-email} is on, and only after the password is verified. */
public class EmailNotVerifiedException extends AuthException {

    public EmailNotVerifiedException() {
        super("AUTH_EMAIL_NOT_VERIFIED", ErrorType.FORBIDDEN,
                "Please verify your email address. Check your inbox or request a new verification email.");
    }
}
