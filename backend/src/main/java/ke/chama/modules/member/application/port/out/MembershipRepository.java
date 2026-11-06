package ke.chama.modules.member.application.port.out;

import ke.chama.modules.member.domain.model.ChamaRole;
import ke.chama.modules.member.domain.model.Membership;
import ke.chama.modules.member.domain.model.MembershipStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.UUID;

/** Every query is also restricted to the current tenant by Hibernate and row-level security. */
public interface MembershipRepository {

    Membership save(Membership membership);

    Optional<Membership> findByIdAndChamaId(UUID id, UUID chamaId);

    Optional<Membership> findByChamaIdAndUserId(UUID chamaId, UUID userId);

    Optional<Membership> findByChamaIdAndUserIdAndStatus(UUID chamaId, UUID userId, MembershipStatus status);

    Page<Membership> findByChamaId(UUID chamaId, Pageable pageable);

    Page<Membership> findByChamaIdAndStatus(UUID chamaId, MembershipStatus status, Pageable pageable);

    long countByChamaIdAndStatus(UUID chamaId, MembershipStatus status);

    java.util.List<Membership> findByChamaIdAndStatusIn(UUID chamaId, java.util.Collection<MembershipStatus> statuses);

    long countActiveWithRole(UUID chamaId, ChamaRole role);

    int nextMemberNumber(UUID chamaId);
}
