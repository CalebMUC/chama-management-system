package ke.chama.shared.domain.exception;

/** A value failed a domain invariant, e.g. a malformed phone number. Maps to HTTP 400. */
public class DomainValidationException extends DomainException {

    public DomainValidationException(String code, String message) {
        super(code, ErrorType.VALIDATION, message);
    }
}
