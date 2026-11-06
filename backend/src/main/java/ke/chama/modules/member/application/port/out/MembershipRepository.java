package ke.chama.modules.member.application.port.out;

import ke.chama.modules.member.domain.model.ChamaRole;
import ke.chama.modules.member.domain.model.Membership;
import ke.chama.modules.member.domain.model.MembershipStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MembershipRepository {

    Membership save(Membership membership);

    Optional<Membership> findByIdAndChamaId(UUID id, UUID chamaId);

    Optional<Membership> findByChamaIdAndUserIdAndStatus(UUID chamaId, UUID userId, MembershipStatus status);

    boolean existsByChamaIdAndInvitedEmailAndStatusIn(UUID chamaId, String invitedEmail, Collection<MembershipStatus> statuses);

    boolean existsByChamaIdAndUserIdAndStatusIn(UUID chamaId, UUID userId, Collection<MembershipStatus> statuses);

    Page<Membership> findByChamaId(UUID chamaId, Pageable pageable);

    Page<Membership> findByChamaIdAndStatus(UUID chamaId, MembershipStatus status, Pageable pageable);

    List<Membership> findByUserIdAndStatusIn(UUID userId, Collection<MembershipStatus> statuses);

    /** Open invitations addressed to this email (the user may not have been linked yet). */
    List<Membership> findByInvitedEmailAndStatus(String invitedEmail, MembershipStatus status);

    long countActiveWithRole(UUID chamaId, ChamaRole role);

    int nextMemberNumber(UUID chamaId);
}
