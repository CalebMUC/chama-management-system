package ke.chama.modules.member.domain.exception;

import ke.chama.modules.member.domain.model.MembershipStatus;
import ke.chama.shared.domain.exception.DomainException;
import ke.chama.shared.domain.exception.ErrorType;

public class InvalidMembershipStateException extends DomainException {

    public InvalidMembershipStateException(String action, MembershipStatus current) {
        super("MEMBER_INVALID_STATE", ErrorType.BUSINESS_RULE,
                "Cannot " + action + " a membership that is " + current);
    }
}
