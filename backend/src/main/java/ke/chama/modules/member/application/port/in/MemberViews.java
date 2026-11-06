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

    /** Name and phone are null until the invitee has an account. */
    public record MemberView(UUID membershipId,
                             UUID chamaId,
                             Integer memberNumber,
                             UUID userId,
                             String email,
                             String firstName,
                             String lastName,
                             String phoneNumber,
                             MembershipStatus status,
                             Set<ChamaRole> roles,
                             Instant invitedAt,
                             Instant joinedAt,
                             String suspensionReason) {

        public static MemberView of(Membership m, UserSummary user) {
            return new MemberView(m.getId(), m.getChamaId(), m.getMemberNumber(), m.getUserId(),
                    user != null ? user.email() : m.getInvitedEmail(),
                    user != null ? user.firstName() : null,
                    user != null ? user.lastName() : null,
                    user != null ? user.phoneNumber() : null,
                    m.getStatus(), Set.copyOf(m.getRoles()), m.getInvitedAt(), m.getJoinedAt(), m.getSuspensionReason());
        }
    }

    /** The caller's own membership and what they may do; the frontend uses it to show or hide actions. */
    public record MyMembershipView(MemberView membership, Set<ChamaPermission> permissions) {
    }

    public record MyChamaView(UUID chamaId,
                              String chamaName,
                              String currency,
                              UUID membershipId,
                              Integer memberNumber,
                              MembershipStatus status,
                              Set<ChamaRole> roles) {
    }

    public record InvitationView(UUID membershipId,
                                 UUID chamaId,
                                 String chamaName,
                                 Instant invitedAt,
                                 String invitedByName) {
    }
}
