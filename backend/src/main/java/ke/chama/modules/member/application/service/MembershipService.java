package ke.chama.modules.member.application.service;

import ke.chama.modules.audit.api.AuditEntry;
import ke.chama.modules.audit.api.AuditTrail;
import ke.chama.modules.auth.api.UserDirectory;
import ke.chama.modules.auth.api.UserProvisioning;
import ke.chama.modules.auth.api.UserProvisioning.NewAccount;
import ke.chama.modules.auth.api.UserSummary;
import ke.chama.modules.chama.api.ChamaDirectory;
import ke.chama.modules.chama.api.ChamaSummary;
import ke.chama.modules.chama.api.LicenseSummary;
import ke.chama.modules.member.application.port.in.MemberViews.InvitationView;
import ke.chama.modules.member.application.port.in.MemberViews.MemberKycView;
import ke.chama.modules.member.application.port.in.MemberViews.MemberView;
import ke.chama.modules.member.application.port.in.MemberViews.MyMembershipView;
import ke.chama.modules.member.application.port.in.MembershipCommands;
import ke.chama.modules.member.application.port.in.MembershipQueries;
import ke.chama.modules.member.application.port.out.MemberKycRepository;
import ke.chama.modules.member.application.port.out.MembershipRepository;
import ke.chama.modules.member.application.port.out.RolePermissionRepository;
import ke.chama.modules.member.domain.event.MemberInvited;
import ke.chama.modules.member.domain.event.MembershipActivated;
import ke.chama.modules.member.domain.exception.MemberExceptions.IdDocumentTakenException;
import ke.chama.modules.member.domain.exception.MemberExceptions.LastChairpersonException;
import ke.chama.modules.member.domain.exception.MemberExceptions.MemberAlreadyExistsException;
import ke.chama.modules.member.domain.exception.MemberExceptions.MemberLimitReachedException;
import ke.chama.modules.member.domain.exception.MemberExceptions.MembershipNotFoundException;
import ke.chama.modules.member.domain.exception.MemberExceptions.SelfManagementNotAllowedException;
import ke.chama.modules.member.domain.model.ChamaRole;
import ke.chama.modules.member.domain.model.MemberKyc;
import ke.chama.modules.member.domain.model.Membership;
import ke.chama.modules.member.domain.model.MembershipStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Members of one chama. {@code chamaId} is always the caller's tenant; every query is also
 * restricted to it by Hibernate and row-level security, so a wrong id finds nothing.
 */
@Service
@Transactional
public class MembershipService implements MembershipCommands, MembershipQueries {

    static final String TARGET = "MEMBERSHIP";

    private final MembershipRepository memberships;
    private final RolePermissionRepository rolePermissions;
    private final UserDirectory userDirectory;
    private final ChamaDirectory chamaDirectory;
    private final AuditTrail auditTrail;
    private final ApplicationEventPublisher events;
    private final UserProvisioning provisioning;
    private final MemberKycRepository kycRecords;
    private final Clock clock;
    private final ZoneId zone;

    public MembershipService(MembershipRepository memberships,
                             RolePermissionRepository rolePermissions,
                             UserDirectory userDirectory,
                             ChamaDirectory chamaDirectory,
                             AuditTrail auditTrail,
                             ApplicationEventPublisher events,
                             UserProvisioning provisioning,
                             MemberKycRepository kycRecords,
                             Clock clock,
                             @Value("${chama.time-zone:Africa/Nairobi}") String timeZone) {
        this.memberships = memberships;
        this.rolePermissions = rolePermissions;
        this.userDirectory = userDirectory;
        this.chamaDirectory = chamaDirectory;
        this.auditTrail = auditTrail;
        this.events = events;
        this.provisioning = provisioning;
        this.kycRecords = kycRecords;
        this.clock = clock;
        this.zone = ZoneId.of(timeZone);
    }

    // ---- commands -----------------------------------------------------------------------

    @Override
    public InvitationView invite(UUID chamaId, UUID actorUserId, String email) {
        Instant now = clock.instant();
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (userDirectory.findByEmail(normalized).isPresent()) {
            throw new MemberAlreadyExistsException(normalized);
        }
        ChamaSummary chama = chamaDirectory.find(chamaId).orElseThrow(() -> new MembershipNotFoundException(chamaId));
        auditTrail.record(AuditEntry.of(chamaId, actorUserId, "MEMBER_INVITED", "INVITATION", normalized)
                .with(Map.of("email", normalized)));
        String inviterName = userDirectory.findById(actorUserId).map(UserSummary::fullName).orElse("A chama official");
        events.publishEvent(new MemberInvited(UUID.randomUUID(), now, chamaId, chama.name(), chama.code(),
                normalized, inviterName));
        return new InvitationView(normalized, chama.code(), now);
    }

    @Override
    public MemberView addMember(UUID chamaId, UUID actorUserId, NewMember member) {
        Instant now = clock.instant();
        requireRoomForAnotherMember(chamaId);
        String email = member.email() == null ? "" : member.email().trim().toLowerCase(Locale.ROOT);
        if (userDirectory.findByEmail(email).isPresent()) {
            throw new MemberAlreadyExistsException(email);
        }
        requireUsableKyc(chamaId, null, member.kyc(), now);

        UserSummary user = provisioning.createMemberAccount(new NewAccount(email, member.phoneNumber(),
                member.temporaryPassword(), member.firstName(), member.lastName()));
        // Flushed: the KYC row below is inserted with plain SQL and references the membership.
        Membership membership = memberships.saveAndFlush(Membership.added(chamaId, user.id(), user.email(),
                memberships.nextMemberNumber(chamaId), actorUserId, now));
        kycRecords.save(chamaId, membership.getId(), member.kyc(), actorUserId, now);
        audit(chamaId, actorUserId, "MEMBER_ADDED", membership,
                Map.of("email", user.email(), "memberNumber", membership.getMemberNumber()));
        return MemberView.of(membership, user);
    }

    @Override
    public MemberKycView updateKyc(UUID chamaId, UUID membershipId, UUID actorUserId, MemberKyc kyc) {
        Instant now = clock.instant();
        Membership membership = load(chamaId, membershipId);
        requireUsableKyc(chamaId, membershipId, kyc, now);
        boolean existed = kycRecords.find(chamaId, membershipId).isPresent();
        kycRecords.save(chamaId, membershipId, kyc, actorUserId, now);
        // The audit log says that the record changed, not the identity data itself.
        audit(chamaId, actorUserId, existed ? "MEMBER_KYC_UPDATED" : "MEMBER_KYC_RECORDED", membership, Map.of());
        return MemberKycView.of(membershipId, kyc, actorUserId, now);
    }

    @Override
    public MemberView approve(UUID chamaId, UUID membershipId, UUID actorUserId) {
        Instant now = clock.instant();
        Membership membership = load(chamaId, membershipId);
        requireNotSelf(membership, actorUserId);
        requireRoomForAnotherMember(chamaId);
        membership.approve(actorUserId, memberships.nextMemberNumber(chamaId), now);
        audit(chamaId, actorUserId, "MEMBER_APPROVED", membership, Map.of("memberNumber", membership.getMemberNumber()));

        UserSummary user = userDirectory.findById(membership.getUserId()).orElse(null);
        String chamaName = chamaDirectory.find(chamaId).map(ChamaSummary::name).orElse("your chama");
        events.publishEvent(new MembershipActivated(UUID.randomUUID(), now, chamaId, chamaName, membership.getId(),
                membership.getUserId(), user != null ? user.email() : membership.getEmail(),
                user != null ? user.firstName() : "member", membership.getMemberNumber()));
        return MemberView.of(membership, user);
    }

    @Override
    public MemberView reject(UUID chamaId, UUID membershipId, UUID actorUserId) {
        Membership membership = load(chamaId, membershipId);
        requireNotSelf(membership, actorUserId);
        membership.reject(actorUserId, clock.instant());
        audit(chamaId, actorUserId, "MEMBER_REJECTED", membership, Map.of());
        return view(membership);
    }

    @Override
    public MemberView suspend(UUID chamaId, UUID membershipId, UUID actorUserId, String reason) {
        Membership membership = load(chamaId, membershipId);
        requireNotSelf(membership, actorUserId);
        if (membership.hasRole(ChamaRole.CHAIRPERSON) && membership.isActive()) {
            requireAnotherChairperson(chamaId);
        }
        membership.suspend(actorUserId, reason, clock.instant());
        audit(chamaId, actorUserId, "MEMBER_SUSPENDED", membership,
                reason == null ? Map.of() : Map.of("reason", reason));
        return view(membership);
    }

    @Override
    public MemberView reactivate(UUID chamaId, UUID membershipId, UUID actorUserId) {
        Membership membership = load(chamaId, membershipId);
        requireNotSelf(membership, actorUserId);
        membership.reactivate();
        audit(chamaId, actorUserId, "MEMBER_REACTIVATED", membership, Map.of());
        return view(membership);
    }

    @Override
    public MemberView changeRoles(UUID chamaId, UUID membershipId, UUID actorUserId, Set<ChamaRole> roles) {
        Membership membership = load(chamaId, membershipId);
        requireNotSelf(membership, actorUserId);
        Set<ChamaRole> before = EnumSet.copyOf(membership.getRoles());
        boolean losesChair = membership.hasRole(ChamaRole.CHAIRPERSON) && !roles.contains(ChamaRole.CHAIRPERSON);
        if (losesChair && membership.isActive()) {
            requireAnotherChairperson(chamaId);
        }
        membership.changeRoles(roles);
        audit(chamaId, actorUserId, "MEMBER_ROLES_CHANGED", membership,
                Map.of("before", names(before), "after", names(membership.getRoles())));
        return view(membership);
    }

    // ---- queries ------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Page<MemberView> members(UUID chamaId, MembershipStatus status, Pageable pageable) {
        Page<Membership> page = status == null
                ? memberships.findByChamaId(chamaId, pageable)
                : memberships.findByChamaIdAndStatus(chamaId, status, pageable);
        Map<UUID, UserSummary> users = userDirectory.findByIds(page.stream()
                .map(Membership::getUserId).collect(Collectors.toSet()));
        return page.map(m -> MemberView.of(m, users.get(m.getUserId())));
    }

    @Override
    @Transactional(readOnly = true)
    public MyMembershipView myMembership(UUID chamaId, UUID userId) {
        Membership membership = memberships.findByChamaIdAndUserId(chamaId, userId)
                .orElseThrow(() -> new MembershipNotFoundException(userId));
        return new MyMembershipView(view(membership), rolePermissions.effectivePermissions(chamaId, userId));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MemberKycView> kyc(UUID chamaId, UUID membershipId) {
        load(chamaId, membershipId);
        return kycRecords.find(chamaId, membershipId)
                .map(k -> MemberKycView.of(k.membershipId(), k.kyc(), k.updatedBy(), k.updatedAt()));
    }

    // ---- helpers ------------------------------------------------------------------------

    private void requireUsableKyc(UUID chamaId, UUID membershipId, MemberKyc kyc, Instant now) {
        kyc.requireAdultOn(LocalDate.ofInstant(now, zone));
        if (kycRecords.idDocumentTaken(chamaId, kyc.idType(), kyc.idNumber(), membershipId)) {
            throw new IdDocumentTakenException();
        }
    }

    private Membership load(UUID chamaId, UUID membershipId) {
        return memberships.findByIdAndChamaId(membershipId, chamaId)
                .orElseThrow(() -> new MembershipNotFoundException(membershipId));
    }

    private static void requireNotSelf(Membership membership, UUID actorUserId) {
        if (membership.belongsTo(actorUserId)) {
            throw new SelfManagementNotAllowedException();
        }
    }

    private void requireAnotherChairperson(UUID chamaId) {
        if (memberships.countActiveWithRole(chamaId, ChamaRole.CHAIRPERSON) <= 1) {
            throw new LastChairpersonException();
        }
    }

    /** The licence caps active members; suspended members keep their seat. */
    private void requireRoomForAnotherMember(UUID chamaId) {
        int maxMembers = chamaDirectory.license(chamaId).map(LicenseSummary::maxMembers).orElse(Integer.MAX_VALUE);
        long seated = memberships.countByChamaIdAndStatus(chamaId, MembershipStatus.ACTIVE)
                + memberships.countByChamaIdAndStatus(chamaId, MembershipStatus.SUSPENDED);
        if (seated >= maxMembers) {
            throw new MemberLimitReachedException(maxMembers);
        }
    }

    private void audit(UUID chamaId, UUID actor, String action, Membership membership, Map<String, ?> details) {
        auditTrail.record(AuditEntry.of(chamaId, actor, action, TARGET, membership.getId()).with(details));
    }

    private MemberView view(Membership membership) {
        return MemberView.of(membership, userDirectory.findById(membership.getUserId()).orElse(null));
    }

    private static List<String> names(Set<ChamaRole> roles) {
        return roles.stream().map(Enum::name).sorted().toList();
    }
}
