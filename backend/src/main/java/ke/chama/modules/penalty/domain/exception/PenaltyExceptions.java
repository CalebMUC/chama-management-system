package ke.chama.modules.penalty.domain.exception;

import ke.chama.modules.penalty.domain.model.Penalty;
import ke.chama.shared.domain.exception.DomainException;
import ke.chama.shared.domain.exception.ErrorType;

/** Penalty errors. Codes are prefixed PENALTY_. */
public final class PenaltyExceptions {

    private PenaltyExceptions() {
    }

    public static class InvalidPenaltyException extends DomainException {
        public InvalidPenaltyException(String message) {
            super("PENALTY_INVALID", ErrorType.VALIDATION, message);
        }
    }

    public static class PenaltyNotFoundException extends DomainException {
        public PenaltyNotFoundException() {
            super("PENALTY_NOT_FOUND", ErrorType.NOT_FOUND, "Penalty not found");
        }
    }

    public static class PenaltyClosedException extends DomainException {
        public PenaltyClosedException(Penalty.Status status) {
            super("PENALTY_CLOSED", ErrorType.CONFLICT, "This penalty is already " + status.name().toLowerCase());
        }
    }

    /** Accountability: another official fines, collects from or waives for you. */
    public static class SelfHandlingNotAllowedException extends DomainException {
        public SelfHandlingNotAllowedException() {
            super("PENALTY_SELF_HANDLING", ErrorType.FORBIDDEN,
                    "You cannot fine yourself, waive your own penalty or record your own payment. Another official must do it.");
        }
    }

    public static class NotASeatedMemberException extends DomainException {
        public NotASeatedMemberException() {
            super("PENALTY_MEMBER_NOT_FOUND", ErrorType.NOT_FOUND,
                    "That person is not an active or suspended member of this chama");
        }
    }

    public static class DuplicateReferenceException extends DomainException {
        public DuplicateReferenceException(String reference) {
            super("PENALTY_DUPLICATE_REFERENCE", ErrorType.CONFLICT,
                    "A penalty payment with reference " + reference + " has already been recorded");
        }
    }
}
