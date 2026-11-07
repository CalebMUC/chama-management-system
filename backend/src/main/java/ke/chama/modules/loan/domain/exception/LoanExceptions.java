package ke.chama.modules.loan.domain.exception;

import ke.chama.modules.loan.domain.model.Loan;
import ke.chama.shared.domain.exception.DomainException;
import ke.chama.shared.domain.exception.ErrorType;

import java.util.List;

/** Loan errors. Codes are prefixed LOAN_. */
public final class LoanExceptions {

    private LoanExceptions() {
    }

    public static class InvalidLoanException extends DomainException {
        public InvalidLoanException(String message) {
            super("LOAN_INVALID", ErrorType.VALIDATION, message);
        }
    }

    /** The member may not borrow this (or anything) now; the message lists every reason. */
    public static class NotEligibleException extends DomainException {
        public NotEligibleException(List<String> reasons) {
            super("LOAN_NOT_ELIGIBLE", ErrorType.BUSINESS_RULE, String.join(" ", reasons));
        }
    }

    public static class LoanNotFoundException extends DomainException {
        public LoanNotFoundException() {
            super("LOAN_NOT_FOUND", ErrorType.NOT_FOUND, "Loan not found");
        }
    }

    public static class WrongLoanStatusException extends DomainException {
        public WrongLoanStatusException(String action, Loan.Status status) {
            super("LOAN_WRONG_STATUS", ErrorType.CONFLICT,
                    "Cannot " + action + " a loan that is " + status.name().toLowerCase().replace('_', ' '));
        }
    }

    /** Accountability: officials act on other members' loans, never their own. */
    public static class SelfHandlingNotAllowedException extends DomainException {
        public SelfHandlingNotAllowedException() {
            super("LOAN_SELF_HANDLING", ErrorType.FORBIDDEN,
                    "You cannot approve, reject, disburse or record repayments on your own loan. Another official must do it.");
        }
    }

    /** A guarantor has a stake in the loan, so they cannot also approve it. */
    public static class GuarantorCannotApproveException extends DomainException {
        public GuarantorCannotApproveException() {
            super("LOAN_GUARANTOR_CANNOT_APPROVE", ErrorType.FORBIDDEN,
                    "You guarantee this loan, so another official must approve it");
        }
    }

    public static class AlreadyApprovedException extends DomainException {
        public AlreadyApprovedException() {
            super("LOAN_ALREADY_APPROVED", ErrorType.CONFLICT, "You have already approved this loan");
        }
    }

    public static class NotTheBorrowerException extends DomainException {
        public NotTheBorrowerException() {
            super("LOAN_NOT_BORROWER", ErrorType.FORBIDDEN, "Only the borrower can do this");
        }
    }

    public static class NotAGuarantorException extends DomainException {
        public NotAGuarantorException() {
            super("LOAN_NOT_GUARANTOR", ErrorType.FORBIDDEN, "You were not asked to guarantee this loan");
        }
    }

    public static class GuarantorAlreadyRespondedException extends DomainException {
        public GuarantorAlreadyRespondedException() {
            super("LOAN_GUARANTOR_RESPONDED", ErrorType.CONFLICT, "You have already responded to this guarantee request");
        }
    }

    public static class NotASeatedMemberException extends DomainException {
        public NotASeatedMemberException(String who) {
            super("LOAN_MEMBER_NOT_FOUND", ErrorType.NOT_FOUND, who + " is not an active member of this chama");
        }
    }

    public static class InsufficientFundsException extends DomainException {
        public InsufficientFundsException(String account, String available) {
            super("LOAN_INSUFFICIENT_FUNDS", ErrorType.BUSINESS_RULE,
                    "The chama's " + account + " holds only " + available + ", not enough to disburse this loan");
        }
    }

    public static class DuplicateReferenceException extends DomainException {
        public DuplicateReferenceException(String reference) {
            super("LOAN_DUPLICATE_REFERENCE", ErrorType.CONFLICT,
                    "A loan transaction with reference " + reference + " has already been recorded");
        }
    }

    public static class InvalidLoanProductException extends DomainException {
        public InvalidLoanProductException(String message) {
            super("LOAN_PRODUCT_INVALID", ErrorType.VALIDATION, message);
        }
    }

    public static class LoanProductNotFoundException extends DomainException {
        public LoanProductNotFoundException() {
            super("LOAN_PRODUCT_NOT_FOUND", ErrorType.NOT_FOUND, "Loan product not found");
        }
    }

    public static class DuplicateLoanProductCodeException extends DomainException {
        public DuplicateLoanProductCodeException(String code) {
            super("LOAN_PRODUCT_CODE_TAKEN", ErrorType.CONFLICT, "A loan product with code " + code + " already exists");
        }
    }

    /** The default loan product's terms are the chama's loan settings, and it is always offered. */
    public static class DefaultLoanProductRuleException extends DomainException {
        public DefaultLoanProductRuleException(String message) {
            super("LOAN_PRODUCT_DEFAULT_RULE", ErrorType.BUSINESS_RULE, message);
        }
    }

    public static class LoanProductInactiveException extends DomainException {
        public LoanProductInactiveException(String name) {
            super("LOAN_PRODUCT_INACTIVE", ErrorType.BUSINESS_RULE, name + " is no longer offered");
        }
    }
}
