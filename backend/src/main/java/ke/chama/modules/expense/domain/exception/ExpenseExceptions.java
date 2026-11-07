package ke.chama.modules.expense.domain.exception;

import ke.chama.modules.expense.domain.model.Expense;
import ke.chama.shared.domain.exception.DomainException;
import ke.chama.shared.domain.exception.ErrorType;

/** Expense errors. Codes are prefixed EXPENSE_. */
public final class ExpenseExceptions {

    private ExpenseExceptions() {
    }

    public static class InvalidExpenseException extends DomainException {
        public InvalidExpenseException(String message) {
            super("EXPENSE_INVALID", ErrorType.VALIDATION, message);
        }
    }

    public static class InvalidExpenseAccountException extends DomainException {
        public InvalidExpenseAccountException(String message) {
            super("EXPENSE_ACCOUNT_INVALID", ErrorType.VALIDATION, message);
        }
    }

    public static class InvalidExpenseInvestmentException extends DomainException {
        public InvalidExpenseInvestmentException(String message) {
            super("EXPENSE_INVESTMENT_INVALID", ErrorType.VALIDATION, message);
        }
    }

    public static class ExpenseNotFoundException extends DomainException {
        public ExpenseNotFoundException() {
            super("EXPENSE_NOT_FOUND", ErrorType.NOT_FOUND, "Expense not found");
        }
    }

    public static class WrongExpenseStatusException extends DomainException {
        public WrongExpenseStatusException(String action, Expense.Status status) {
            super("EXPENSE_WRONG_STATUS", ErrorType.CONFLICT,
                    "Cannot " + action + " an expense that is " + status.name().toLowerCase().replace('_', ' '));
        }
    }

    /** Maker-checker: whoever records an expense cannot also approve or reject it. */
    public static class SelfApprovalNotAllowedException extends DomainException {
        public SelfApprovalNotAllowedException() {
            super("EXPENSE_SELF_APPROVAL", ErrorType.FORBIDDEN,
                    "You recorded this expense, so other officials must approve or reject it");
        }
    }

    public static class AlreadyApprovedException extends DomainException {
        public AlreadyApprovedException() {
            super("EXPENSE_ALREADY_APPROVED", ErrorType.CONFLICT, "You have already approved this expense");
        }
    }

    public static class NotTheRequesterException extends DomainException {
        public NotTheRequesterException() {
            super("EXPENSE_NOT_REQUESTER", ErrorType.FORBIDDEN, "Only the official who recorded this expense can cancel it");
        }
    }

    public static class InsufficientFundsException extends DomainException {
        public InsufficientFundsException(String account, String available) {
            super("EXPENSE_INSUFFICIENT_FUNDS", ErrorType.BUSINESS_RULE,
                    "The chama's " + account + " holds only " + available + ", not enough to pay this expense");
        }
    }

    public static class DuplicateReferenceException extends DomainException {
        public DuplicateReferenceException(String reference) {
            super("EXPENSE_DUPLICATE_REFERENCE", ErrorType.CONFLICT,
                    "An expense payment with reference " + reference + " has already been recorded");
        }
    }
}
