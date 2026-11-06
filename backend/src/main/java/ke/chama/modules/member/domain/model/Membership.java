package ke.chama.modules.member.domain.model;

import jakarta.persistence.*;
import ke.chama.modules.member.domain.exception.InvalidMembershipStateException;
import ke.chama.shared.domain.AggregateRoot;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * One person's membership in one chama: lifecycle (see {@link MembershipStatus}) and roles.
 * Every transition records who did it and when.
 * <p>
 * {@code userId} is null until the invitee accepts; the invitation is addressed by email
 * because the person may not have an account yet.
 */
@Entity
@Table(name = "memberships", schema = "member")
public class Membership extends AggregateRoot {

    @Id
    private UUID id;

    @Column(name = "chama_id", nullable = false, updatable = false)
    private UUID chamaId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "invited_email", nullable = false, updatable = false)
    private String invitedEmail;

    /** Human-friendly number within the chama (1, 2, 3...), assigned when approved. */
    @Column(name = "member_number")
    private Integer memberNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MembershipStatus status;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "membership_roles", schema = "member", joinColumns = @JoinColumn(name = "membership_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 30)
    private Set<ChamaRole> roles = new HashSet<>();

    @Column(name = "invited_by")
    private UUID invitedBy;

    @Column(name = "invited_at", nullable = false)
    private Instant invitedAt;

    @Column(name = "responded_at")
    private Instant respondedAt;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "joined_at")
    private Instant joinedAt;

    @Column(name = "suspended_by")
    private UUID suspendedBy;

    @Column(name = "suspended_at")
    private Instant suspendedAt;

    @Column(name = "suspension_reason", length = 500)
    private String suspensionReason;

    /** Required by JPA. */
    protected Membership() {
    }

    private Membership(UUID chamaId, String email, MembershipStatus status, Instant now) {
        this.id = UUID.randomUUID();
        this.chamaId = Objects.requireNonNull(chamaId);
        this.invitedEmail = normalizeEmail(email);
        this.status = status;
        this.invitedAt = now;
        this.roles.add(ChamaRole.MEMBER);
    }

    /** The person who created the chama: active immediately, member #1, chairperson. */
    public static Membership founder(UUID chamaId, UUID userId, String email, Instant now) {
        Membership membership = new Membership(chamaId, email, MembershipStatus.ACTIVE, now);
        membership.userId = userId;
        membership.memberNumber = 1;
        membership.joinedAt = now;
        membership.decidedAt = now;
        membership.respondedAt = now;
        membership.roles.add(ChamaRole.CHAIRPERSON);
        return membership;
    }

    public static Membership invite(UUID chamaId, String email, UUID invitedBy, Instant now) {
        Membership membership = new Membership(chamaId, email, MembershipStatus.INVITED, now);
        membership.invitedBy = invitedBy;
        return membership;
    }

    public void accept(UUID acceptingUserId, Instant now) {
        requireStatus("accept", MembershipStatus.INVITED);
        this.userId = Objects.requireNonNull(acceptingUserId);
        this.status = MembershipStatus.PENDING_APPROVAL;
        this.respondedAt = now;
    }

    public void decline(UUID decliningUserId, Instant now) {
        requireStatus("decline", MembershipStatus.INVITED);
        this.userId = decliningUserId;
        this.status = MembershipStatus.DECLINED;
        this.respondedAt = now;
    }

    public void approve(UUID approverId, int assignedMemberNumber, Instant now) {
        requireStatus("approve", MembershipStatus.PENDING_APPROVAL);
        this.status = MembershipStatus.ACTIVE;
        this.memberNumber = assignedMemberNumber;
        this.decidedBy = approverId;
        this.decidedAt = now;
        this.joinedAt = now;
    }

    public void reject(UUID approverId, Instant now) {
        requireStatus("reject", MembershipStatus.PENDING_APPROVAL);
        this.status = MembershipStatus.REJECTED;
        this.decidedBy = approverId;
        this.decidedAt = now;
    }

    public void suspend(UUID by, String reason, Instant now) {
        requireStatus("suspend", MembershipStatus.ACTIVE);
        this.status = MembershipStatus.SUSPENDED;
        this.suspendedBy = by;
        this.suspendedAt = now;
        this.suspensionReason = reason == null || reason.isBlank() ? null : reason.trim();
    }

    public void reactivate() {
        requireStatus("reactivate", MembershipStatus.SUSPENDED);
        this.status = MembershipStatus.ACTIVE;
        this.suspendedBy = null;
        this.suspendedAt = null;
        this.suspensionReason = null;
    }

    /** Replaces the roles. MEMBER is always kept. Only for ACTIVE memberships. */
    public void changeRoles(Set<ChamaRole> newRoles) {
        requireStatus("change the roles of", MembershipStatus.ACTIVE);
        EnumSet<ChamaRole> updated = newRoles.isEmpty() ? EnumSet.noneOf(ChamaRole.class) : EnumSet.copyOf(newRoles);
        updated.add(ChamaRole.MEMBER);
        this.roles.clear();
        this.roles.addAll(updated);
    }

    public boolean isActive() {
        return status == MembershipStatus.ACTIVE;
    }

    public boolean hasRole(ChamaRole role) {
        return roles.contains(role);
    }

    public boolean belongsTo(UUID someUserId) {
        return userId != null && userId.equals(someUserId);
    }

    public boolean isAddressedTo(String email) {
        return email != null && invitedEmail.equals(normalizeEmail(email));
    }

    private void requireStatus(String action, MembershipStatus required) {
        if (status != required) {
            throw new InvalidMembershipStateException(action, status);
        }
    }

    static String normalizeEmail(String email) {
        return Objects.requireNonNull(email, "email").trim().toLowerCase(Locale.ROOT);
    }

    public UUID getId() {
        return id;
    }

    public UUID getChamaId() {
        return chamaId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getInvitedEmail() {
        return invitedEmail;
    }

    public Integer getMemberNumber() {
        return memberNumber;
    }

    public MembershipStatus getStatus() {
        return status;
    }

    public Set<ChamaRole> getRoles() {
        return Collections.unmodifiableSet(roles);
    }

    public UUID getInvitedBy() {
        return invitedBy;
    }

    public Instant getInvitedAt() {
        return invitedAt;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public Instant getSuspendedAt() {
        return suspendedAt;
    }

    public String getSuspensionReason() {
        return suspensionReason;
    }
}
