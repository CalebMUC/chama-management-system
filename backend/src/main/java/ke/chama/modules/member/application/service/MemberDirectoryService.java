package ke.chama.modules.member.application.service;

import ke.chama.modules.auth.api.UserDirectory;
import ke.chama.modules.auth.api.UserSummary;
import ke.chama.modules.member.api.MemberDirectory;
import ke.chama.modules.member.api.MemberSummary;
import ke.chama.modules.member.application.port.out.MembershipRepository;
import ke.chama.modules.member.domain.model.Membership;
import ke.chama.modules.member.domain.model.MembershipStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
class MemberDirectoryService implements MemberDirectory {

    private static final Set<MembershipStatus> SEATED = EnumSet.of(MembershipStatus.ACTIVE, MembershipStatus.SUSPENDED);

    private final MembershipRepository memberships;
    private final UserDirectory users;

    MemberDirectoryService(MembershipRepository memberships, UserDirectory users) {
        this.memberships = memberships;
        this.users = users;
    }

    @Override
    public List<MemberSummary> seatedMembers(UUID chamaId) {
        List<Membership> seated = memberships.findByChamaIdAndStatusIn(chamaId, SEATED);
        Map<UUID, UserSummary> accounts = users.findByIds(seated.stream().map(Membership::getUserId)
                .collect(Collectors.toSet()));
        return seated.stream()
                .sorted(Comparator.comparing(Membership::getMemberNumber, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(m -> summary(m, accounts.get(m.getUserId())))
                .toList();
    }

    @Override
    public Optional<MemberSummary> seatedMember(UUID chamaId, UUID userId) {
        return memberships.findByChamaIdAndUserId(chamaId, userId)
                .filter(m -> SEATED.contains(m.getStatus()))
                .map(m -> summary(m, users.findById(userId).orElse(null)));
    }

    private static MemberSummary summary(Membership m, UserSummary user) {
        return new MemberSummary(m.getId(), m.getUserId(), m.getMemberNumber(),
                user == null ? null : user.firstName(), user == null ? null : user.lastName(),
                user == null ? m.getEmail() : user.email(), m.isActive(), m.getJoinedAt());
    }
}
