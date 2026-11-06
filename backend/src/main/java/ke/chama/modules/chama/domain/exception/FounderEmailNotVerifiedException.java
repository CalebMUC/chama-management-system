package ke.chama.modules.chama.domain.exception;

import ke.chama.shared.domain.exception.DomainException;
import ke.chama.shared.domain.exception.ErrorType;

/** Chama officials must be reachable by email, so founding a chama requires a verified address. */
public class FounderEmailNotVerifiedException extends DomainException {

    public FounderEmailNotVerifiedException() {
        super("CHAMA_EMAIL_NOT_VERIFIED", ErrorType.FORBIDDEN,
                "Verify your email address before creating a chama");
    }
}
