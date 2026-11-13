package ke.chama.modules.member.application.port.in;

import ke.chama.modules.member.application.port.in.MemberViews.InvitationView;
import ke.chama.modules.member.application.port.in.MemberViews.MemberKycView;
import ke.chama.modules.member.application.port.in.MemberViews.MemberView;
import ke.chama.modules.member.domain.model.ChamaRole;
import ke.chama.modules.member.domain.model.MemberKyc;

import java.util.Set;
import java.util.UUID;

/**
 * Membership changes in the caller's chama. {@code actorUserId} is always the authenticated
 * caller; permission to act is checked at the controller, business rules here. Every change is
 * audit-logged.
 */
public interface MembershipCommands {

    /** Emails a link to join the chama. Joining still needs an official's approval. */
    InvitationView invite(UUID chamaId, UUID actorUserId, String email);

    /**
     * Opens the member's account with a temporary password and their KYC details. The member is
     * ACTIVE at once (within the licence's member limit), receives no email, and must replace the
     * password at first sign-in.
     */
    MemberView addMember(UUID chamaId, UUID actorUserId, NewMember member);

    /** Records or corrects a member's KYC details. */
    MemberKycView updateKyc(UUID chamaId, UUID membershipId, UUID actorUserId, MemberKyc kyc);

    /** Activates an applicant, within the licence's member limit. */
    MemberView approve(UUID chamaId, UUID membershipId, UUID actorUserId);

    MemberView reject(UUID chamaId, UUID membershipId, UUID actorUserId);

    MemberView suspend(UUID chamaId, UUID membershipId, UUID actorUserId, String reason);

    MemberView reactivate(UUID chamaId, UUID membershipId, UUID actorUserId);

    MemberView changeRoles(UUID chamaId, UUID membershipId, UUID actorUserId, Set<ChamaRole> roles);

    /** {@code temporaryPassword} follows the self-registration password rules. */
    record NewMember(String email, String phoneNumber, String firstName, String lastName, String temporaryPassword,
                     MemberKyc kyc) {

        @Override
        public String toString() {
            return "NewMember[email=" + email + ", temporaryPassword=***]";
        }
    }
}
