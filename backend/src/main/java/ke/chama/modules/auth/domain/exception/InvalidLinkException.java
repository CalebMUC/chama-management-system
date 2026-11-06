package ke.chama.modules.auth.domain.exception;

import ke.chama.shared.domain.exception.ErrorType;

/** Verification or reset link that is unknown, already used, superseded by a newer one, or expired. */
public class InvalidLinkException extends AuthException {

    public InvalidLinkException() {
        super("AUTH_LINK_INVALID", ErrorType.VALIDATION,
                "This link is invalid or has expired. Please request a new one.");
    }
}
