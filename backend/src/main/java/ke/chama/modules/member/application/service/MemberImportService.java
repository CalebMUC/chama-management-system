package ke.chama.modules.member.application.service;

import ke.chama.modules.audit.api.AuditEntry;
import ke.chama.modules.audit.api.AuditTrail;
import ke.chama.modules.auth.api.UserDirectory;
import ke.chama.modules.member.api.MemberImport;
import ke.chama.modules.member.api.MemberSummary;
import ke.chama.modules.member.application.port.out.MembershipRepository;
import ke.chama.modules.member.domain.model.ChamaRole;
import ke.chama.modules.member.domain.model.Membership;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Seats members brought over from a chama's spreadsheets, in the importer's transaction. */
@Service
class MemberImportService implements MemberImport {

    private final MembershipRepository memberships;
    private final UserDirectory users;
    private final AuditTrail auditTrail;
    private final Clock clock;
    private final ZoneId zone;

    MemberImportService(MembershipRepository memberships, UserDirectory users, AuditTrail auditTrail, Clock clock,
                        @Value("${chama.time-zone:Africa/Nairobi}") String timeZone) {
        this.memberships = memberships;
        this.users = users;
        this.auditTrail = auditTrail;
        this.clock = clock;
        this.zone = ZoneId.of(timeZone);
    }

    @Override
    public Set<String> roleNames() {
        return Arrays.stream(ChamaRole.values()).map(Enum::name).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public MemberSummary importMember(UUID chamaId, UUID userId, String email, LocalDate joinedOn, Set<String> roles,
                                      UUID importedBy) {
        EnumSet<ChamaRole> held = EnumSet.of(ChamaRole.MEMBER);
        roles.forEach(r -> held.add(ChamaRole.valueOf(r.trim().toUpperCase(Locale.ROOT))));
        Instant joinedAt = joinedOn.atStartOfDay(zone).toInstant();
        Membership membership = memberships.save(Membership.imported(chamaId, userId, email,
                memberships.nextMemberNumber(chamaId), joinedAt, held, importedBy, clock.instant()));
        auditTrail.record(AuditEntry.of(chamaId, importedBy, "MEMBER_IMPORTED", MembershipService.TARGET, membership.getId())
                .with(Map.of("email", membership.getEmail(), "memberNumber", membership.getMemberNumber(),
                        "joinedOn", joinedOn.toString(),
                        "roles", held.stream().map(Enum::name).sorted().toList())));
        var user = users.findById(userId).orElse(null);
        return new MemberSummary(membership.getId(), userId, membership.getMemberNumber(),
                user == null ? null : user.firstName(), user == null ? null : user.lastName(), membership.getEmail(),
                true, membership.getJoinedAt());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public LocalDate correctJoinDate(UUID chamaId, UUID userId, LocalDate joinedOn, UUID actorUserId) {
        Membership membership = memberships.findByChamaIdAndUserId(chamaId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Not a member of this chama: " + userId));
        LocalDate before = membership.getJoinedAt() == null ? null : LocalDate.ofInstant(membership.getJoinedAt(), zone);
        membership.correctJoinedAt(joinedOn.atStartOfDay(zone).toInstant());
        memberships.save(membership);
        auditTrail.record(AuditEntry.of(chamaId, actorUserId, "MEMBER_JOIN_DATE_CORRECTED", MembershipService.TARGET,
                membership.getId()).with(Map.of("joinedOn", joinedOn.toString(),
                "previously", before == null ? "" : before.toString())));
        return before;
    }
}
