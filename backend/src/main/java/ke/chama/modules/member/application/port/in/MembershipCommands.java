package ke.chama.modules.member.application.port.in;

import ke.chama.modules.member.application.port.in.MemberViews.MemberView;
import ke.chama.modules.member.domain.model.ChamaRole;

import java.util.Set;
import java.util.UUID;

/**
 * Membership changes. {@code actorUserId} is always the authenticated caller; permission to act
 * is checked at the controller, business rules here. Every change is audit-logged.
 */
public interface MembershipCommands {

    MemberView invite(UUID chamaId, UUID actorUserId, String email);

    /** By the invitee: requires a verified email that matches the invitation. */
    MemberView accept(UUID chamaId, UUID membershipId, UUID actorUserId);

    MemberView decline(UUID chamaId, UUID membershipId, UUID actorUserId);

    MemberView approve(UUID chamaId, UUID membershipId, UUID actorUserId);

    MemberView reject(UUID chamaId, UUID membershipId, UUID actorUserId);

    MemberView suspend(UUID chamaId, UUID membershipId, UUID actorUserId, String reason);

    MemberView reactivate(UUID chamaId, UUID membershipId, UUID actorUserId);

    MemberView changeRoles(UUID chamaId, UUID membershipId, UUID actorUserId, Set<ChamaRole> roles);
}
