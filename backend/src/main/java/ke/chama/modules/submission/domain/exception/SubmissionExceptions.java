package ke.chama.modules.submission.domain.exception;

import ke.chama.modules.submission.domain.model.PaymentSubmission;
import ke.chama.shared.domain.exception.DomainException;
import ke.chama.shared.domain.exception.ErrorType;

/** Payment submission errors. Codes are prefixed SUBMISSION_. */
public final class SubmissionExceptions {

    private SubmissionExceptions() {
    }

    public static class InvalidSubmissionException extends DomainException {
        public InvalidSubmissionException(String message) {
            super("SUBMISSION_INVALID", ErrorType.VALIDATION, message);
        }
    }

    /** The penalty or loan named is not the submitter's, or cannot take a payment now. */
    public static class InvalidTargetException extends DomainException {
        public InvalidTargetException(String message) {
            super("SUBMISSION_TARGET_INVALID", ErrorType.BUSINESS_RULE, message);
        }
    }

    public static class SubmissionNotFoundException extends DomainException {
        public SubmissionNotFoundException() {
            super("SUBMISSION_NOT_FOUND", ErrorType.NOT_FOUND, "Payment submission not found");
        }
    }

    public static class WrongSubmissionStatusException extends DomainException {
        public WrongSubmissionStatusException(String action, PaymentSubmission.Status status) {
            super("SUBMISSION_WRONG_STATUS", ErrorType.CONFLICT,
                    "Cannot " + action + " a payment submission that is " + status.name().toLowerCase());
        }
    }

    /** Separation of duties: nobody confirms or rejects a payment they reported themselves. */
    public static class SelfReviewNotAllowedException extends DomainException {
        public SelfReviewNotAllowedException() {
            super("SUBMISSION_SELF_REVIEW", ErrorType.FORBIDDEN,
                    "You reported this payment, so another official must review it");
        }
    }

    public static class NotTheSubmitterException extends DomainException {
        public NotTheSubmitterException() {
            super("SUBMISSION_NOT_SUBMITTER", ErrorType.FORBIDDEN, "Only the member who reported this payment can cancel it");
        }
    }

    /** The official lacks the record permission for this kind of payment. */
    public static class ReviewNotAllowedException extends DomainException {
        public ReviewNotAllowedException(PaymentSubmission.Kind kind) {
            super("ACCESS_DENIED", ErrorType.FORBIDDEN,
                    "You need the " + kind.reviewPermission().name() + " permission to review this payment");
        }
    }

    public static class DuplicateReferenceException extends DomainException {
        public DuplicateReferenceException(String reference) {
            super("SUBMISSION_DUPLICATE_REFERENCE", ErrorType.CONFLICT,
                    "A payment with reference " + reference + " has already been reported or recorded");
        }
    }

    /** Same code as the licence check on endpoints, so clients handle both alike. */
    public static class FeatureNotLicensedException extends DomainException {
        public FeatureNotLicensedException(String feature) {
            super("LICENSE_FEATURE_NOT_INCLUDED", ErrorType.FORBIDDEN,
                    "Your chama's plan does not include " + feature.toLowerCase());
        }
    }
}
