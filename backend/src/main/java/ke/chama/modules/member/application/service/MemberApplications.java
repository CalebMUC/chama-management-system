package ke.chama.modules.member.application.service;

import ke.chama.modules.audit.api.AuditEntry;
import ke.chama.modules.audit.api.AuditTrail;
import ke.chama.modules.auth.domain.event.UserRegisteredEvent;
import ke.chama.modules.member.application.port.out.MembershipRepository;
import ke.chama.modules.member.domain.model.Membership;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Someone who registers with a chama's code applies to join it. Their membership is created in
 * the SAME transaction as the account, so no account can exist without one. An official then
 * approves or rejects the application.
 */
@Component
class MemberApplications {

    private final MembershipRepository memberships;
    private final AuditTrail auditTrail;

    MemberApplications(MembershipRepository memberships, AuditTrail auditTrail) {
        this.memberships = memberships;
        this.auditTrail = auditTrail;
    }

    @EventListener(condition = "#event.selfRegistered()")
    @Transactional(propagation = Propagation.MANDATORY)
    void on(UserRegisteredEvent event) {
        Membership application = memberships.save(
                Membership.application(event.tenantId(), event.userId(), event.email(), event.occurredAt()));
        auditTrail.record(AuditEntry.of(event.tenantId(), event.userId(), "MEMBER_APPLIED",
                MembershipService.TARGET, application.getId()).with(Map.of("email", event.email())));
    }
}
