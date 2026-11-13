package ke.chama.modules.member.application.port.in;

import ke.chama.modules.member.application.port.in.MemberViews.MemberKycView;
import ke.chama.modules.member.application.port.in.MemberViews.MemberView;
import ke.chama.modules.member.application.port.in.MemberViews.MyMembershipView;
import ke.chama.modules.member.domain.model.MembershipStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.UUID;

public interface MembershipQueries {

    /** {@code status} is optional. */
    Page<MemberView> members(UUID chamaId, MembershipStatus status, Pageable pageable);

    /** The caller's own membership in any status, e.g. to show "waiting for approval". */
    MyMembershipView myMembership(UUID chamaId, UUID userId);

    /** Empty if no KYC details were recorded for the membership yet. */
    Optional<MemberKycView> kyc(UUID chamaId, UUID membershipId);
}
