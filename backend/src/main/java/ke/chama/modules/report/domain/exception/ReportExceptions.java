package ke.chama.modules.report.domain.exception;

import ke.chama.shared.domain.exception.DomainException;
import ke.chama.shared.domain.exception.ErrorType;

/** Report errors. Codes are prefixed REPORT_. */
public final class ReportExceptions {

    private ReportExceptions() {
    }

    public static class InvalidPeriodException extends DomainException {
        public InvalidPeriodException(String message) {
            super("REPORT_INVALID_PERIOD", ErrorType.VALIDATION, message);
        }
    }

    public static class NotACashAccountException extends DomainException {
        public NotACashAccountException(String code) {
            super("REPORT_NOT_A_CASH_ACCOUNT", ErrorType.VALIDATION,
                    code + " is not one of the chama's cash accounts (cash on hand, bank or M-Pesa)");
        }
    }

    public static class MemberNotFoundException extends DomainException {
        public MemberNotFoundException() {
            super("REPORT_MEMBER_NOT_FOUND", ErrorType.NOT_FOUND,
                    "That person is not an active or suspended member of this chama");
        }
    }
}
