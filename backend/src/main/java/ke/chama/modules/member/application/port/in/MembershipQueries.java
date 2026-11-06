package ke.chama.modules.member.application.port.in;

import ke.chama.modules.member.application.port.in.MemberViews.InvitationView;
import ke.chama.modules.member.application.port.in.MemberViews.MemberView;
import ke.chama.modules.member.application.port.in.MemberViews.MyChamaView;
import ke.chama.modules.member.application.port.in.MemberViews.MyMembershipView;
import ke.chama.modules.member.domain.model.MembershipStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

public interface MembershipQueries {

    /** {@code status} is optional. */
    Page<MemberView> members(UUID chamaId, MembershipStatus status, Pageable pageable);

    MyMembershipView myMembership(UUID chamaId, UUID userId);

    /** Chamas the user is active, suspended or pending approval in. */
    List<MyChamaView> myChamas(UUID userId);

    /** Open invitations addressed to the user's email. */
    List<InvitationView> myInvitations(UUID userId);
}
