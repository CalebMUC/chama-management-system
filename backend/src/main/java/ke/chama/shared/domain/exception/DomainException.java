package ke.chama.shared.domain.exception;

import java.util.Objects;

/**
 * Base for all business exceptions.
 * <p>
 * {@code code} is a stable, machine-readable identifier such as {@code LOAN_NOT_ELIGIBLE}.
 * Clients branch on the code, never on the message, so messages can be reworded freely.
 * Codes are prefixed with the owning module.
 */
public abstract class DomainException extends RuntimeException {

    private final String code;
    private final ErrorType type;

    protected DomainException(String code, ErrorType type, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code must not be null");
        this.type = Objects.requireNonNull(type, "type must not be null");
    }

    protected DomainException(String code, ErrorType type, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code must not be null");
        this.type = Objects.requireNonNull(type, "type must not be null");
    }

    public String getCode() {
        return code;
    }

    public ErrorType getType() {
        return type;
    }
}
