package ke.chama.modules.member.application.port.in;

import ke.chama.modules.auth.api.UserSummary;
import ke.chama.modules.member.domain.model.ChamaRole;
import ke.chama.modules.member.domain.model.Membership;
import ke.chama.modules.member.domain.model.MembershipStatus;
import ke.chama.shared.security.ChamaPermission;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Read models returned by the member use cases. */
public final class MemberViews {

    private MemberViews() {
    }

    /** Name and phone come from the member's account; null only if the account was removed. */
    public record MemberView(UUID membershipId,
                             UUID chamaId,
                             Integer memberNumber,
                             UUID userId,
                             String email,
                             String firstName,
                             String lastName,
                             String phoneNumber,
                             boolean emailVerified,
                             MembershipStatus status,
                             Set<ChamaRole> roles,
                             Instant appliedAt,
                             Instant joinedAt,
                             String suspensionReason) {

        public static MemberView of(Membership m, UserSummary user) {
            return new MemberView(m.getId(), m.getChamaId(), m.getMemberNumber(), m.getUserId(),
                    user != null ? user.email() : m.getEmail(),
                    user != null ? user.firstName() : null,
                    user != null ? user.lastName() : null,
                    user != null ? user.phoneNumber() : null,
                    user != null && user.emailVerified(),
                    m.getStatus(), Set.copyOf(m.getRoles()), m.getAppliedAt(), m.getJoinedAt(), m.getSuspensionReason());
        }
    }

    /**
     * The caller's own membership and what they may do; the frontend uses it to show or hide
     * actions. {@code permissions} is empty unless the membership is ACTIVE.
     */
    public record MyMembershipView(MemberView membership, Set<ChamaPermission> permissions) {
    }

    /**
     * An invitation email that was sent. The invitee registers with {@code chamaCode} and is
     * then approved like any other applicant.
     */
    public record InvitationView(String email, String chamaCode, Instant invitedAt) {
    }
}
