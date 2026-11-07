package ke.chama.modules.investment.domain.exception;

import ke.chama.modules.investment.domain.model.Investment;
import ke.chama.shared.domain.exception.DomainException;
import ke.chama.shared.domain.exception.ErrorType;

/** Investment errors. Codes are prefixed INVESTMENT_. */
public final class InvestmentExceptions {

    private InvestmentExceptions() {
    }

    public static class InvalidInvestmentException extends DomainException {
        public InvalidInvestmentException(String message) {
            super("INVESTMENT_INVALID", ErrorType.VALIDATION, message);
        }
    }

    public static class InvestmentNotFoundException extends DomainException {
        public InvestmentNotFoundException() {
            super("INVESTMENT_NOT_FOUND", ErrorType.NOT_FOUND, "Investment not found");
        }
    }

    public static class WrongInvestmentStatusException extends DomainException {
        public WrongInvestmentStatusException(String action, Investment.Status status) {
            super("INVESTMENT_WRONG_STATUS", ErrorType.CONFLICT,
                    "Cannot " + action + " an investment that is " + status.name().toLowerCase().replace('_', ' '));
        }
    }

    /** Maker-checker: whoever proposes an investment cannot also approve or reject it. */
    public static class SelfApprovalNotAllowedException extends DomainException {
        public SelfApprovalNotAllowedException() {
            super("INVESTMENT_SELF_APPROVAL", ErrorType.FORBIDDEN,
                    "You proposed this investment, so other officials must approve or reject it");
        }
    }

    public static class AlreadyApprovedException extends DomainException {
        public AlreadyApprovedException() {
            super("INVESTMENT_ALREADY_APPROVED", ErrorType.CONFLICT, "You have already approved this investment");
        }
    }

    public static class NotTheProposerException extends DomainException {
        public NotTheProposerException() {
            super("INVESTMENT_NOT_PROPOSER", ErrorType.FORBIDDEN, "Only the official who proposed this investment can cancel it");
        }
    }

    public static class InsufficientFundsException extends DomainException {
        public InsufficientFundsException(String account, String available) {
            super("INVESTMENT_INSUFFICIENT_FUNDS", ErrorType.BUSINESS_RULE,
                    "The chama's " + account + " holds only " + available + ", not enough to fund this investment");
        }
    }

    public static class DuplicateReferenceException extends DomainException {
        public DuplicateReferenceException(String reference) {
            super("INVESTMENT_DUPLICATE_REFERENCE", ErrorType.CONFLICT,
                    "An investment transaction with reference " + reference + " has already been recorded");
        }
    }
}
