package ke.chama.modules.member.infrastructure.persistence;

import ke.chama.modules.member.application.port.out.MembershipRepository;
import ke.chama.modules.member.domain.model.ChamaRole;
import ke.chama.modules.member.domain.model.Membership;
import ke.chama.modules.member.domain.model.MembershipStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

interface JpaMembershipRepository extends JpaRepository<Membership, UUID>, MembershipRepository {

    @Override
    @Query("""
            select count(m) from Membership m join m.roles r
             where m.chamaId = :chamaId and m.status = ke.chama.modules.member.domain.model.MembershipStatus.ACTIVE
               and r = :role""")
    long countActiveWithRole(@Param("chamaId") UUID chamaId, @Param("role") ChamaRole role);

    @Override
    @Query("select coalesce(max(m.memberNumber), 0) + 1 from Membership m where m.chamaId = :chamaId")
    int nextMemberNumber(@Param("chamaId") UUID chamaId);
}
