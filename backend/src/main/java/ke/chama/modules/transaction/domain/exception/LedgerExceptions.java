package ke.chama.modules.transaction.domain.exception;

import ke.chama.shared.domain.exception.DomainException;
import ke.chama.shared.domain.exception.ErrorType;

/** Ledger errors. Codes are prefixed LEDGER_. */
public final class LedgerExceptions {

    private LedgerExceptions() {
    }

    public static class UnbalancedEntryException extends DomainException {
        public UnbalancedEntryException(String message) {
            super("LEDGER_UNBALANCED_ENTRY", ErrorType.VALIDATION, message);
        }
    }

    public static class InvalidEntryException extends DomainException {
        public InvalidEntryException(String message) {
            super("LEDGER_INVALID_ENTRY", ErrorType.VALIDATION, message);
        }
    }

    public static class InvalidAccountException extends DomainException {
        public InvalidAccountException(String message) {
            super("LEDGER_INVALID_ACCOUNT", ErrorType.VALIDATION, message);
        }
    }

    public static class AccountNotFoundException extends DomainException {
        public AccountNotFoundException(String account) {
            super("LEDGER_ACCOUNT_NOT_FOUND", ErrorType.NOT_FOUND, "No ledger account " + account + " in this chama");
        }
    }

    public static class AccountInactiveException extends DomainException {
        public AccountInactiveException(String code) {
            super("LEDGER_ACCOUNT_INACTIVE", ErrorType.BUSINESS_RULE, "Account " + code + " is inactive and cannot be posted to");
        }
    }

    public static class DuplicateAccountCodeException extends DomainException {
        public DuplicateAccountCodeException(String code) {
            super("LEDGER_ACCOUNT_CODE_TAKEN", ErrorType.CONFLICT, "Account code " + code + " is already used in this chama");
        }
    }

    /** Member-ledger accounts need the member on every line; other accounts must not have one. */
    public static class MemberRequiredException extends DomainException {
        public MemberRequiredException(String message) {
            super("LEDGER_MEMBER_MISMATCH", ErrorType.VALIDATION, message);
        }
    }

    public static class CurrencyMismatchException extends DomainException {
        public CurrencyMismatchException(String code, String expected) {
            super("LEDGER_CURRENCY_MISMATCH", ErrorType.VALIDATION, "Account " + code + " is kept in " + expected);
        }
    }

    public static class EntryNotFoundException extends DomainException {
        public EntryNotFoundException() {
            super("LEDGER_ENTRY_NOT_FOUND", ErrorType.NOT_FOUND, "Journal entry not found");
        }
    }

    public static class AlreadyReversedException extends DomainException {
        public AlreadyReversedException(long number) {
            super("LEDGER_ALREADY_REVERSED", ErrorType.CONFLICT, "Entry #" + number + " has already been reversed");
        }
    }

    public static class CannotReverseReversalException extends DomainException {
        public CannotReverseReversalException() {
            super("LEDGER_CANNOT_REVERSE_REVERSAL", ErrorType.BUSINESS_RULE,
                    "A reversal entry cannot itself be reversed; post a new entry instead");
        }
    }
}
