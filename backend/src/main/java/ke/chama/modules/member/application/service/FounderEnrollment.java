package ke.chama.modules.member.application.service;

import ke.chama.modules.audit.api.AuditEntry;
import ke.chama.modules.audit.api.AuditTrail;
import ke.chama.modules.chama.domain.event.ChamaCreated;
import ke.chama.modules.member.application.port.out.MembershipRepository;
import ke.chama.modules.member.domain.model.Membership;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Makes the founder chairperson and seeds the default role permissions, in the SAME
 * transaction that creates the chama.
 * <p>
 * Deliberately a synchronous {@code @EventListener}, not an async {@code @ApplicationModuleListener}:
 * a chama that nobody can manage must never be committed. The chama module stays unaware of
 * memberships; it only publishes the event.
 */
@Component
class FounderEnrollment {

    private final MembershipRepository memberships;
    private final RolePermissionService rolePermissions;
    private final AuditTrail auditTrail;

    FounderEnrollment(MembershipRepository memberships, RolePermissionService rolePermissions, AuditTrail auditTrail) {
        this.memberships = memberships;
        this.rolePermissions = rolePermissions;
        this.auditTrail = auditTrail;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    void on(ChamaCreated event) {
        Membership founder = memberships.save(
                Membership.founder(event.chamaId(), event.founderUserId(), event.founderEmail(), event.occurredAt()));
        rolePermissions.seedDefaults(event.chamaId());

        auditTrail.record(AuditEntry.of(event.chamaId(), event.founderUserId(), "MEMBER_FOUNDER_ENROLLED",
                MembershipService.TARGET, founder.getId()).with(Map.of("roles", founder.getRoles().stream()
                .map(Enum::name).sorted().toList())));
    }
}
