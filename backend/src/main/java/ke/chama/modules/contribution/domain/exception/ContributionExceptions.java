package ke.chama.modules.contribution.domain.exception;

import ke.chama.shared.domain.exception.DomainException;
import ke.chama.shared.domain.exception.ErrorType;

/** Contribution errors. Codes are prefixed CONTRIBUTION_. */
public final class ContributionExceptions {

    private ContributionExceptions() {
    }

    public static class InvalidPaymentException extends DomainException {
        public InvalidPaymentException(String message) {
            super("CONTRIBUTION_INVALID_PAYMENT", ErrorType.VALIDATION, message);
        }
    }

    public static class NotASeatedMemberException extends DomainException {
        public NotASeatedMemberException() {
            super("CONTRIBUTION_MEMBER_NOT_FOUND", ErrorType.NOT_FOUND,
                    "That person is not an active or suspended member of this chama");
        }
    }

    /** Accountability: another official records (or reverses) your own contribution. */
    public static class SelfRecordingNotAllowedException extends DomainException {
        public SelfRecordingNotAllowedException() {
            super("CONTRIBUTION_SELF_RECORDING", ErrorType.FORBIDDEN,
                    "You cannot record or reverse your own contribution. Another official must do it.");
        }
    }

    public static class DuplicateReferenceException extends DomainException {
        public DuplicateReferenceException(String reference) {
            super("CONTRIBUTION_DUPLICATE_REFERENCE", ErrorType.CONFLICT,
                    "A payment with reference " + reference + " has already been recorded");
        }
    }

    public static class PaymentNotFoundException extends DomainException {
        public PaymentNotFoundException() {
            super("CONTRIBUTION_NOT_FOUND", ErrorType.NOT_FOUND, "Contribution payment not found");
        }
    }

    public static class PaymentAlreadyReversedException extends DomainException {
        public PaymentAlreadyReversedException() {
            super("CONTRIBUTION_ALREADY_REVERSED", ErrorType.CONFLICT, "This payment has already been reversed");
        }
    }

    public static class PeriodNotFoundException extends DomainException {
        public PeriodNotFoundException() {
            super("CONTRIBUTION_PERIOD_NOT_FOUND", ErrorType.NOT_FOUND, "Contribution period not found");
        }
    }
}
