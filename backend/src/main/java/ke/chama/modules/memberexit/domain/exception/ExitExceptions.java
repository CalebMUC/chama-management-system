package ke.chama.modules.memberexit.domain.exception;

import ke.chama.modules.memberexit.domain.model.MemberExit;
import ke.chama.shared.domain.exception.DomainException;
import ke.chama.shared.domain.exception.ErrorType;

import java.util.List;

/** Member exit errors. Codes are prefixed EXIT_. */
public final class ExitExceptions {

    private ExitExceptions() {
    }

    public static class InvalidExitException extends DomainException {
        public InvalidExitException(String message) {
            super("EXIT_INVALID", ErrorType.VALIDATION, message);
        }
    }

    public static class ExitNotFoundException extends DomainException {
        public ExitNotFoundException() {
            super("EXIT_NOT_FOUND", ErrorType.NOT_FOUND, "Member exit not found");
        }
    }

    public static class WrongExitStatusException extends DomainException {
        public WrongExitStatusException(String action, MemberExit.Status status) {
            super("EXIT_WRONG_STATUS", ErrorType.CONFLICT,
                    "Cannot " + action + " an exit that is " + status.name().toLowerCase().replace('_', ' '));
        }
    }

    /** Something the member must sort out before they can leave; the message lists every reason. */
    public static class ExitBlockedException extends DomainException {
        public ExitBlockedException(List<String> blockers) {
            super("EXIT_BLOCKED", ErrorType.BUSINESS_RULE,
                    "The member cannot leave yet: " + String.join("; ", blockers));
        }
    }

    public static class ExitInProgressException extends DomainException {
        public ExitInProgressException() {
            super("EXIT_IN_PROGRESS", ErrorType.CONFLICT, "This member's exit is already in progress");
        }
    }

    /** Balances or debts moved after the settlement was approved, so it no longer holds. */
    public static class SettlementChangedException extends DomainException {
        public SettlementChangedException() {
            super("EXIT_SETTLEMENT_CHANGED", ErrorType.CONFLICT,
                    "The member's savings, loans or penalties have changed since this settlement was worked out."
                            + " Reject or cancel this exit and start a new one, so officials approve the right figures");
        }
    }

    /** Maker-checker: whoever starts an exit cannot approve or reject it, and nobody handles their own. */
    public static class SelfHandlingNotAllowedException extends DomainException {
        public SelfHandlingNotAllowedException(String message) {
            super("EXIT_SELF_HANDLING", ErrorType.FORBIDDEN, message);
        }
    }

    public static class AlreadyApprovedException extends DomainException {
        public AlreadyApprovedException() {
            super("EXIT_ALREADY_APPROVED", ErrorType.CONFLICT, "You have already approved this exit");
        }
    }

    public static class NotTheRequesterException extends DomainException {
        public NotTheRequesterException() {
            super("EXIT_NOT_REQUESTER", ErrorType.FORBIDDEN, "Only the official who started this exit can cancel it");
        }
    }

    public static class InsufficientFundsException extends DomainException {
        public InsufficientFundsException(String account, String available) {
            super("EXIT_INSUFFICIENT_FUNDS", ErrorType.BUSINESS_RULE,
                    "The chama's " + account + " holds only " + available + ", not enough to pay out this settlement");
        }
    }

    public static class DuplicateReferenceException extends DomainException {
        public DuplicateReferenceException(String reference) {
            super("EXIT_DUPLICATE_REFERENCE", ErrorType.CONFLICT,
                    "An exit payout with reference " + reference + " has already been recorded");
        }
    }

    public static class NotASeatedMemberException extends DomainException {
        public NotASeatedMemberException() {
            super("EXIT_NOT_A_MEMBER", ErrorType.NOT_FOUND, "There is no active or suspended member with that id");
        }
    }
}
