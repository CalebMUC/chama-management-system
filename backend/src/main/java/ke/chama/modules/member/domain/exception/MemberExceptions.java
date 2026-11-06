package ke.chama.modules.member.domain.exception;

import ke.chama.shared.domain.exception.DomainException;
import ke.chama.shared.domain.exception.ErrorType;

import java.util.UUID;

/** The member module's business errors. Codes are prefixed MEMBER_ or ROLE_. */
public final class MemberExceptions {

    private MemberExceptions() {
    }

    public static class MembershipNotFoundException extends DomainException {
        public MembershipNotFoundException(UUID membershipId) {
            super("MEMBER_NOT_FOUND", ErrorType.NOT_FOUND, "Membership not found: " + membershipId);
        }
    }

    public static class MemberAlreadyExistsException extends DomainException {
        public MemberAlreadyExistsException(String email) {
            super("MEMBER_ALREADY_EXISTS", ErrorType.CONFLICT, email + " already has an account in this chama");
        }
    }

    /** Accountability: nobody changes their own roles or status; another official must. */
    public static class SelfManagementNotAllowedException extends DomainException {
        public SelfManagementNotAllowedException() {
            super("MEMBER_SELF_MANAGEMENT", ErrorType.FORBIDDEN,
                    "You cannot change your own roles or status. Another official must do it.");
        }
    }

    public static class LastChairpersonException extends DomainException {
        public LastChairpersonException() {
            super("MEMBER_LAST_CHAIRPERSON", ErrorType.BUSINESS_RULE,
                    "A chama must always have at least one active chairperson. Appoint another chairperson first.");
        }
    }

    /** The chama's licence allows this many active members; renew or upgrade it to add more. */
    public static class MemberLimitReachedException extends DomainException {
        public MemberLimitReachedException(int maxMembers) {
            super("LICENSE_MEMBER_LIMIT_REACHED", ErrorType.BUSINESS_RULE,
                    "Your chama's licence allows " + maxMembers + " active members. Upgrade the licence to approve more.");
        }
    }

    public static class PermissionLockoutException extends DomainException {
        public PermissionLockoutException() {
            super("ROLE_PERMISSIONS_LOCKOUT", ErrorType.BUSINESS_RULE,
                    "The chairperson role must keep MEMBER_ROLES_MANAGE and ROLE_PERMISSIONS_MANAGE");
        }
    }
}
