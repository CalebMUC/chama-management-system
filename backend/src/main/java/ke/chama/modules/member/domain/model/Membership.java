package ke.chama.modules.member.domain.model;

import jakarta.persistence.*;
import ke.chama.modules.member.domain.exception.InvalidMembershipStateException;
import ke.chama.shared.domain.AggregateRoot;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A user's membership of their chama: lifecycle (see {@link MembershipStatus}) and roles.
 * Every user belongs to exactly one chama, so there is exactly one membership per user, created
 * when they register. Every transition records who did it and when.
 */
@Entity
@Table(name = "memberships", schema = "member")
public class Membership extends AggregateRoot {

    @Id
    private UUID id;

    /** The chama (tenant). Hibernate checks it matches the current tenant on insert. */
    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID chamaId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "email", nullable = false)
    private String email;

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

    @Column(name = "applied_at", nullable = false)
    private Instant appliedAt;

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

    private Membership(UUID chamaId, UUID userId, String email, MembershipStatus status, Instant now) {
        this.id = UUID.randomUUID();
        this.chamaId = Objects.requireNonNull(chamaId, "chamaId");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.email = normalizeEmail(email);
        this.status = status;
        this.appliedAt = now;
        this.roles.add(ChamaRole.MEMBER);
    }

    /** The chama's first administrator: active immediately, member #1, chairperson. */
    public static Membership founder(UUID chamaId, UUID userId, String email, Instant now) {
        Membership membership = new Membership(chamaId, userId, email, MembershipStatus.ACTIVE, now);
        membership.memberNumber = 1;
        membership.joinedAt = now;
        membership.decidedAt = now;
        membership.roles.add(ChamaRole.CHAIRPERSON);
        return membership;
    }

    /**
     * A member brought over from the chama's earlier records: active from the day they actually
     * joined, with the roles they hold. MEMBER is always included.
     */
    public static Membership imported(UUID chamaId, UUID userId, String email, int memberNumber, Instant joinedAt,
                                      Set<ChamaRole> roles, UUID importedBy, Instant now) {
        Membership membership = new Membership(chamaId, userId, email, MembershipStatus.ACTIVE, joinedAt);
        membership.memberNumber = memberNumber;
        membership.joinedAt = joinedAt;
        membership.decidedBy = importedBy;
        membership.decidedAt = now;
        membership.roles.addAll(roles);
        return membership;
    }

    /**
     * An official opened the member's account directly: active from now, with the next member
     * number. Roles beyond MEMBER are given afterwards, like for any member.
     */
    public static Membership added(UUID chamaId, UUID userId, String email, int memberNumber, UUID addedBy, Instant now) {
        Membership membership = new Membership(chamaId, userId, email, MembershipStatus.ACTIVE, now);
        membership.memberNumber = memberNumber;
        membership.joinedAt = now;
        membership.decidedBy = addedBy;
        membership.decidedAt = now;
        return membership;
    }

    /** Someone registered with the chama's code: they wait for an official's approval. */
    public static Membership application(UUID chamaId, UUID userId, String email, Instant now) {
        return new Membership(chamaId, userId, email, MembershipStatus.PENDING_APPROVAL, now);
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
        return userId.equals(someUserId);
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

    public String getEmail() {
        return email;
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

    public Instant getAppliedAt() {
        return appliedAt;
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
