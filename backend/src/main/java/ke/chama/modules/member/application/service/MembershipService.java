package ke.chama.modules.member.application.service;

import ke.chama.modules.audit.api.AuditEntry;
import ke.chama.modules.audit.api.AuditTrail;
import ke.chama.modules.auth.api.UserDirectory;
import ke.chama.modules.auth.api.UserSummary;
import ke.chama.modules.chama.api.ChamaDirectory;
import ke.chama.modules.chama.api.ChamaSummary;
import ke.chama.modules.member.application.port.in.MemberViews.InvitationView;
import ke.chama.modules.member.application.port.in.MemberViews.MemberView;
import ke.chama.modules.member.application.port.in.MemberViews.MyChamaView;
import ke.chama.modules.member.application.port.in.MemberViews.MyMembershipView;
import ke.chama.modules.member.application.port.in.MembershipCommands;
import ke.chama.modules.member.application.port.in.MembershipQueries;
import ke.chama.modules.member.application.port.out.MembershipRepository;
import ke.chama.modules.member.application.port.out.RolePermissionRepository;
import ke.chama.modules.member.domain.event.MemberInvited;
import ke.chama.modules.member.domain.event.MembershipActivated;
import ke.chama.modules.member.domain.exception.MemberExceptions.EmailVerificationRequiredException;
import ke.chama.modules.member.domain.exception.MemberExceptions.InvitationNotForYouException;
import ke.chama.modules.member.domain.exception.MemberExceptions.LastChairpersonException;
import ke.chama.modules.member.domain.exception.MemberExceptions.MemberAlreadyExistsException;
import ke.chama.modules.member.domain.exception.MemberExceptions.MembershipNotFoundException;
import ke.chama.modules.member.domain.exception.MemberExceptions.SelfManagementNotAllowedException;
import ke.chama.modules.member.domain.model.ChamaRole;
import ke.chama.modules.member.domain.model.Membership;
import ke.chama.modules.member.domain.model.MembershipStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
    private final Clock clock;

    public MembershipService(MembershipRepository memberships,
                             RolePermissionRepository rolePermissions,
                             UserDirectory userDirectory,
                             ChamaDirectory chamaDirectory,
                             AuditTrail auditTrail,
                             ApplicationEventPublisher events,
                             Clock clock) {
        this.memberships = memberships;
        this.rolePermissions = rolePermissions;
        this.userDirectory = userDirectory;
        this.chamaDirectory = chamaDirectory;
        this.auditTrail = auditTrail;
        this.events = events;
        this.clock = clock;
    }

    // ---- commands -----------------------------------------------------------------------

    @Override
    public MemberView invite(UUID chamaId, UUID actorUserId, String email) {
        Instant now = clock.instant();
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (memberships.existsByChamaIdAndInvitedEmailAndStatusIn(chamaId, normalized, MembershipStatus.OPEN)) {
            throw new MemberAlreadyExistsException(normalized);
        }
        userDirectory.findByEmail(normalized).ifPresent(existing -> {
            if (memberships.existsByChamaIdAndUserIdAndStatusIn(chamaId, existing.id(), MembershipStatus.OPEN)) {
                throw new MemberAlreadyExistsException(normalized);
            }
        });

        Membership membership = memberships.save(Membership.invite(chamaId, normalized, actorUserId, now));
        audit(chamaId, actorUserId, "MEMBER_INVITED", membership, Map.of("email", normalized));

        String chamaName = chamaDirectory.find(chamaId).map(ChamaSummary::name).orElse("a chama");
        String inviterName = userDirectory.findById(actorUserId).map(UserSummary::fullName).orElse("A chama official");
        events.publishEvent(new MemberInvited(UUID.randomUUID(), now, chamaId, chamaName, membership.getId(),
                normalized, inviterName));
        return view(membership);
    }

    @Override
    public MemberView accept(UUID chamaId, UUID membershipId, UUID actorUserId) {
        Membership membership = load(chamaId, membershipId);
        requireInvitee(membership, actorUserId, true);
        membership.accept(actorUserId, clock.instant());
        audit(chamaId, actorUserId, "MEMBER_INVITATION_ACCEPTED", membership, Map.of());
        return view(membership);
    }

    @Override
    public MemberView decline(UUID chamaId, UUID membershipId, UUID actorUserId) {
        Membership membership = load(chamaId, membershipId);
        requireInvitee(membership, actorUserId, false);
        membership.decline(actorUserId, clock.instant());
        audit(chamaId, actorUserId, "MEMBER_INVITATION_DECLINED", membership, Map.of());
        return view(membership);
    }

    @Override
    public MemberView approve(UUID chamaId, UUID membershipId, UUID actorUserId) {
        Instant now = clock.instant();
        Membership membership = load(chamaId, membershipId);
        requireNotSelf(membership, actorUserId);
        membership.approve(actorUserId, memberships.nextMemberNumber(chamaId), now);
        audit(chamaId, actorUserId, "MEMBER_APPROVED", membership, Map.of("memberNumber", membership.getMemberNumber()));

        UserSummary user = userDirectory.findById(membership.getUserId()).orElse(null);
        String chamaName = chamaDirectory.find(chamaId).map(ChamaSummary::name).orElse("your chama");
        events.publishEvent(new MembershipActivated(UUID.randomUUID(), now, chamaId, chamaName, membership.getId(),
                membership.getUserId(), user != null ? user.email() : membership.getInvitedEmail(),
                user != null ? user.firstName() : "member", membership.getMemberNumber()));
        return view(membership, user);
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
                .map(Membership::getUserId).filter(java.util.Objects::nonNull).collect(Collectors.toSet()));
        return page.map(m -> MemberView.of(m, m.getUserId() == null ? null : users.get(m.getUserId())));
    }

    @Override
    @Transactional(readOnly = true)
    public MyMembershipView myMembership(UUID chamaId, UUID userId) {
        Membership membership = memberships.findByChamaIdAndUserIdAndStatus(chamaId, userId, MembershipStatus.ACTIVE)
                .orElseThrow(() -> new MembershipNotFoundException(userId));
        return new MyMembershipView(view(membership), rolePermissions.effectivePermissions(chamaId, userId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<MyChamaView> myChamas(UUID userId) {
        List<Membership> mine = memberships.findByUserIdAndStatusIn(userId,
                EnumSet.of(MembershipStatus.ACTIVE, MembershipStatus.SUSPENDED, MembershipStatus.PENDING_APPROVAL));
        Map<UUID, ChamaSummary> chamas = chamaDirectory.findAll(mine.stream().map(Membership::getChamaId).toList());
        return mine.stream()
                .filter(m -> chamas.containsKey(m.getChamaId()))
                .map(m -> {
                    ChamaSummary chama = chamas.get(m.getChamaId());
                    return new MyChamaView(chama.id(), chama.name(), chama.currency(), m.getId(),
                            m.getMemberNumber(), m.getStatus(), Set.copyOf(m.getRoles()));
                })
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<InvitationView> myInvitations(UUID userId) {
        UserSummary user = userDirectory.findById(userId).orElse(null);
        if (user == null) {
            return List.of();
        }
        List<Membership> invitations = memberships.findByInvitedEmailAndStatus(user.email(), MembershipStatus.INVITED);
        Map<UUID, ChamaSummary> chamas = chamaDirectory.findAll(invitations.stream().map(Membership::getChamaId).toList());
        Map<UUID, UserSummary> inviters = userDirectory.findByIds(invitations.stream()
                .map(Membership::getInvitedBy).filter(java.util.Objects::nonNull).collect(Collectors.toSet()));
        return invitations.stream()
                .filter(m -> chamas.containsKey(m.getChamaId()))
                .map(m -> new InvitationView(m.getId(), m.getChamaId(), chamas.get(m.getChamaId()).name(), m.getInvitedAt(),
                        m.getInvitedBy() != null && inviters.containsKey(m.getInvitedBy())
                                ? inviters.get(m.getInvitedBy()).fullName() : null))
                .toList();
    }

    // ---- helpers ------------------------------------------------------------------------

    private Membership load(UUID chamaId, UUID membershipId) {
        return memberships.findByIdAndChamaId(membershipId, chamaId)
                .orElseThrow(() -> new MembershipNotFoundException(membershipId));
    }

    /**
     * Invitations are matched by email, so the person must prove they own that inbox. Declining
     * is allowed without verification: it gives the invitee nothing.
     */
    private void requireInvitee(Membership membership, UUID actorUserId, boolean requireVerifiedEmail) {
        UserSummary actor = userDirectory.findById(actorUserId).orElseThrow(InvitationNotForYouException::new);
        if (!membership.isAddressedTo(actor.email())) {
            throw new InvitationNotForYouException();
        }
        if (requireVerifiedEmail && !actor.emailVerified()) {
            throw new EmailVerificationRequiredException();
        }
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

    private void audit(UUID chamaId, UUID actor, String action, Membership membership, Map<String, ?> details) {
        auditTrail.record(AuditEntry.of(chamaId, actor, action, TARGET, membership.getId()).with(details));
    }

    private MemberView view(Membership membership) {
        UserSummary user = membership.getUserId() == null ? null : userDirectory.findById(membership.getUserId()).orElse(null);
        return view(membership, user);
    }

    private static MemberView view(Membership membership, UserSummary user) {
        return MemberView.of(membership, user);
    }

    private static List<String> names(Set<ChamaRole> roles) {
        return roles.stream().map(Enum::name).sorted().toList();
    }
}
