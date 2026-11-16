package ke.chama.modules.member.application.service;

import ke.chama.modules.audit.api.AuditEntry;
import ke.chama.modules.audit.api.AuditTrail;
import ke.chama.modules.member.api.MemberExits;
import ke.chama.modules.member.application.port.out.MembershipRepository;
import ke.chama.modules.member.domain.exception.MemberExceptions.LastChairpersonException;
import ke.chama.modules.member.domain.exception.MemberExceptions.MembershipNotFoundException;
import ke.chama.modules.member.domain.model.ChamaRole;
import ke.chama.modules.member.domain.model.Membership;
import ke.chama.modules.member.domain.model.MembershipStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
class MemberExitService implements MemberExits {

    private final MembershipRepository memberships;
    private final AuditTrail auditTrail;

    MemberExitService(MembershipRepository memberships, AuditTrail auditTrail) {
        this.memberships = memberships;
        this.auditTrail = auditTrail;
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> exitBlockers(UUID chamaId, UUID userId) {
        List<String> blockers = new ArrayList<>();
        Optional<Membership> membership = memberships.findByChamaIdAndUserId(chamaId, userId);
        if (membership.isEmpty() || !seated(membership.get())) {
            blockers.add("Only an active or suspended member can leave the chama");
        } else if (lastChairperson(chamaId, membership.get())) {
            blockers.add("This member is the chama's only active chairperson: make another member chairperson first");
        }
        return blockers;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordExit(UUID chamaId, UUID userId, UUID actorUserId, String reason, Instant exitedAt) {
        Membership membership = memberships.findByChamaIdAndUserId(chamaId, userId)
                .orElseThrow(() -> new MembershipNotFoundException(userId));
        if (seated(membership) && lastChairperson(chamaId, membership)) {
            throw new LastChairpersonException();
        }
        membership.exit(actorUserId, reason, exitedAt);
        memberships.save(membership);
        auditTrail.record(AuditEntry.of(chamaId, actorUserId, "MEMBER_EXITED", MembershipService.TARGET,
                membership.getId()).with(Map.of("memberNumber", String.valueOf(membership.getMemberNumber()),
                "reason", membership.getExitReason() == null ? "" : membership.getExitReason())));
    }

    private static boolean seated(Membership m) {
        return m.getStatus() == MembershipStatus.ACTIVE || m.getStatus() == MembershipStatus.SUSPENDED;
    }

    private boolean lastChairperson(UUID chamaId, Membership m) {
        return m.isActive() && m.hasRole(ChamaRole.CHAIRPERSON)
                && memberships.countActiveWithRole(chamaId, ChamaRole.CHAIRPERSON) <= 1;
    }
}
