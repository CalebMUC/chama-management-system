package ke.chama.modules.account.domain.exception;

import ke.chama.shared.domain.exception.DomainException;
import ke.chama.shared.domain.exception.ErrorType;

/** Savings product and member account errors. Codes are prefixed SAVINGS_PRODUCT_ and MEMBER_ACCOUNT_. */
public final class AccountExceptions {

    private AccountExceptions() {
    }

    public static class InvalidProductException extends DomainException {
        public InvalidProductException(String message) {
            super("SAVINGS_PRODUCT_INVALID", ErrorType.VALIDATION, message);
        }
    }

    public static class ProductNotFoundException extends DomainException {
        public ProductNotFoundException() {
            super("SAVINGS_PRODUCT_NOT_FOUND", ErrorType.NOT_FOUND, "Savings product not found");
        }
    }

    public static class DuplicateProductCodeException extends DomainException {
        public DuplicateProductCodeException(String code) {
            super("SAVINGS_PRODUCT_CODE_TAKEN", ErrorType.CONFLICT, "A savings product with code " + code + " already exists");
        }
    }

    /** The default product follows the chama's settings and is always active and mandatory. */
    public static class DefaultProductRuleException extends DomainException {
        public DefaultProductRuleException(String message) {
            super("SAVINGS_PRODUCT_DEFAULT_RULE", ErrorType.BUSINESS_RULE, message);
        }
    }

    public static class ProductInactiveException extends DomainException {
        public ProductInactiveException(String name) {
            super("SAVINGS_PRODUCT_INACTIVE", ErrorType.BUSINESS_RULE, name + " is no longer offered");
        }
    }

    public static class NoLedgerCodeAvailableException extends DomainException {
        public NoLedgerCodeAvailableException() {
            super("SAVINGS_PRODUCT_NO_LEDGER_CODE", ErrorType.CONFLICT,
                    "Every ledger code for this kind of product is taken; give the product an unused ledgerAccountCode");
        }
    }

    public static class AccountNotFoundException extends DomainException {
        public AccountNotFoundException() {
            super("MEMBER_ACCOUNT_NOT_FOUND", ErrorType.NOT_FOUND, "Member account not found");
        }
    }

    public static class AccountExistsException extends DomainException {
        public AccountExistsException(String accountNumber) {
            super("MEMBER_ACCOUNT_EXISTS", ErrorType.CONFLICT,
                    "The member already has an account in this product (" + accountNumber + ")");
        }
    }

    public static class AccountStateException extends DomainException {
        public AccountStateException(String message) {
            super("MEMBER_ACCOUNT_STATE", ErrorType.BUSINESS_RULE, message);
        }
    }

    public static class NotASeatedMemberException extends DomainException {
        public NotASeatedMemberException() {
            super("MEMBER_ACCOUNT_MEMBER_NOT_FOUND", ErrorType.NOT_FOUND,
                    "That person is not an active or suspended member of this chama");
        }
    }
}
