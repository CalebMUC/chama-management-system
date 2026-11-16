package ke.chama.modules.memberexit.domain.model;

import ke.chama.modules.memberexit.domain.exception.ExitExceptions.AlreadyApprovedException;
import ke.chama.modules.memberexit.domain.exception.ExitExceptions.InvalidExitException;
import ke.chama.modules.memberexit.domain.exception.ExitExceptions.NotTheRequesterException;
import ke.chama.modules.memberexit.domain.exception.ExitExceptions.SelfHandlingNotAllowedException;
import ke.chama.modules.memberexit.domain.exception.ExitExceptions.WrongExitStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A member leaving the chama, with maker-checker approval of their settlement.
 * <pre>
 * PENDING_APPROVAL ──(quorum of officials)──► APPROVED ──(paid out)──► SETTLED
 *        └──── rejected by an approver / cancelled by whoever started it ────┘
 * </pre>
 * Nobody handles their own exit, and whoever started an exit cannot approve or reject it.
 */
public final class MemberExit {

    static final int MAX_TEXT_LENGTH = 300;
    private static final Set<String> METHODS = Set.of("CASH", "BANK", "MPESA");

    public enum Status {
        PENDING_APPROVAL,
        APPROVED,
        SETTLED,
        REJECTED,
        CANCELLED
    }

    private static final Set<Status> UNSETTLED = EnumSet.of(Status.PENDING_APPROVAL, Status.APPROVED);

    private final UUID id;
    private final UUID chamaId;
    private final UUID memberUserId;
    private final String reason;
    private final Settlement settlement;
    private final UUID requestedBy;
    private final int requiredApprovals;
    private final List<Approval> approvals;
    private Status status;
    private UUID rejectedBy;
    private Instant rejectedAt;
    private String rejectionReason;
    private Instant cancelledAt;
    private LocalDate settledOn;
    private UUID settledBy;
    private String paymentMethod;
    private String paymentReference;
    private UUID settlementEntryId;
    private final Instant createdAt;
    private final long version;

    public MemberExit(UUID id, UUID chamaId, UUID memberUserId, String reason, Settlement settlement, UUID requestedBy,
                      int requiredApprovals, List<Approval> approvals, Status status, UUID rejectedBy,
                      Instant rejectedAt, String rejectionReason, Instant cancelledAt, LocalDate settledOn,
                      UUID settledBy, String paymentMethod, String paymentReference, UUID settlementEntryId,
                      Instant createdAt, long version) {
        this.id = Objects.requireNonNull(id);
        this.chamaId = Objects.requireNonNull(chamaId);
        this.memberUserId = Objects.requireNonNull(memberUserId);
        this.reason = reason;
        this.settlement = Objects.requireNonNull(settlement);
        this.requestedBy = Objects.requireNonNull(requestedBy);
        this.requiredApprovals = requiredApprovals;
        this.approvals = new ArrayList<>(approvals);
        this.status = status;
        this.rejectedBy = rejectedBy;
        this.rejectedAt = rejectedAt;
        this.rejectionReason = rejectionReason;
        this.cancelledAt = cancelledAt;
        this.settledOn = settledOn;
        this.settledBy = settledBy;
        this.paymentMethod = paymentMethod;
        this.paymentReference = paymentReference;
        this.settlementEntryId = settlementEntryId;
        this.createdAt = createdAt;
        this.version = version;
    }

    /**
     * Starts an exit. The caller has checked that nothing blocks it (guarantees, loans in progress,
     * the membership itself) and worked out the settlement.
     *
     * @param requiredApprovals the chama's approval quorum when the exit was started
     */
    public static MemberExit request(UUID chamaId, UUID memberUserId, UUID requestedBy, String reason,
                                     Settlement settlement, int requiredApprovals, Instant now) {
        if (memberUserId.equals(requestedBy)) {
            throw new SelfHandlingNotAllowedException("Another official must start your exit from the chama");
        }
        if (settlement.shortfallMinor() > 0) {
            throw new InvalidExitException("The member owes more than their refundable savings");
        }
        return new MemberExit(UUID.randomUUID(), chamaId, memberUserId, text(reason, "reason"), settlement, requestedBy,
                Math.max(requiredApprovals, 1), List.of(), Status.PENDING_APPROVAL, null, null, null, null, null, null,
                null, null, null, now, 0);
    }

    /** One official's approval; the exit is approved once {@code requiredApprovals} distinct officials approve. */
    public void approve(UUID approverUserId, String note, Instant now) {
        requireChecker(approverUserId, "approve");
        requireStatus("approve", Status.PENDING_APPROVAL);
        if (approvals.stream().anyMatch(a -> a.approverUserId().equals(approverUserId))) {
            throw new AlreadyApprovedException();
        }
        String text = note == null || note.isBlank() ? null : note.trim();
        if (text != null && text.length() > MAX_TEXT_LENGTH) {
            throw new InvalidExitException("The note must be at most " + MAX_TEXT_LENGTH + " characters");
        }
        approvals.add(new Approval(approverUserId, text, now));
        if (approvals.size() >= requiredApprovals) {
            status = Status.APPROVED;
        }
    }

    /** Any one approver can turn it down before it is settled. A reason is required. */
    public void reject(UUID officialUserId, String why, Instant now) {
        requireChecker(officialUserId, "reject");
        requireUnsettled("reject");
        rejectionReason = text(why, "reason");
        status = Status.REJECTED;
        rejectedBy = officialUserId;
        rejectedAt = now;
    }

    /** The official who started it withdraws it before it is settled. */
    public void cancel(UUID actorUserId, Instant now) {
        if (!requestedBy.equals(actorUserId)) {
            throw new NotTheRequesterException();
        }
        requireUnsettled("cancel");
        status = Status.CANCELLED;
        cancelledAt = now;
    }

    /**
     * Pays out an approved settlement. A method (and, for M-Pesa and bank, a reference) is needed
     * only when something is paid out. The caller posts it to the ledger and ends the membership.
     */
    public void settle(UUID officialUserId, String method, String reference, LocalDate date, LocalDate today,
                       ZoneId zone) {
        if (memberUserId.equals(officialUserId)) {
            throw new SelfHandlingNotAllowedException("Another official must pay out your exit settlement");
        }
        requireStatus("settle", Status.APPROVED);
        LocalDate on = date == null ? today : date;
        if (on.isAfter(today)) {
            throw new InvalidExitException("A settlement cannot be dated in the future");
        }
        if (on.isBefore(LocalDate.ofInstant(createdAt, zone))) {
            throw new InvalidExitException("A settlement cannot be dated before the exit was started");
        }
        String m = method == null || method.isBlank() ? null : method.trim().toUpperCase(Locale.ROOT);
        String ref = reference == null || reference.isBlank() ? null : reference.trim().toUpperCase(Locale.ROOT);
        if (settlement.payoutMinor() > 0) {
            if (m == null || !METHODS.contains(m)) {
                throw new InvalidExitException("method must be CASH, BANK or MPESA");
            }
            if (ref == null && !m.equals("CASH")) {
                throw new InvalidExitException(m + " payouts need their reference (transaction code or slip number)");
            }
        } else {
            m = null;
            ref = null;
        }
        if (ref != null && ref.length() > 100) {
            throw new InvalidExitException("reference must be at most 100 characters");
        }
        status = Status.SETTLED;
        settledOn = on;
        settledBy = officialUserId;
        paymentMethod = m;
        paymentReference = ref;
    }

    public void settlementPostedAs(UUID entryId) {
        this.settlementEntryId = entryId;
    }

    private void requireChecker(UUID officialUserId, String action) {
        if (memberUserId.equals(officialUserId)) {
            throw new SelfHandlingNotAllowedException("You cannot " + action + " your own exit from the chama");
        }
        if (requestedBy.equals(officialUserId)) {
            throw new SelfHandlingNotAllowedException("You started this exit, so other officials must approve or reject it");
        }
    }

    private void requireStatus(String action, Status required) {
        if (status != required) {
            throw new WrongExitStatusException(action, status);
        }
    }

    private void requireUnsettled(String action) {
        if (!UNSETTLED.contains(status)) {
            throw new WrongExitStatusException(action, status);
        }
    }

    private static String text(String value, String field) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) {
            throw new InvalidExitException("A " + field + " is required");
        }
        if (v.length() > MAX_TEXT_LENGTH) {
            throw new InvalidExitException("The " + field + " must be at most " + MAX_TEXT_LENGTH + " characters");
        }
        return v;
    }

    public boolean inProgress() {
        return UNSETTLED.contains(status);
    }

    public UUID id() { return id; }
    public UUID chamaId() { return chamaId; }
    public UUID memberUserId() { return memberUserId; }
    public String reason() { return reason; }
    public Settlement settlement() { return settlement; }
    public UUID requestedBy() { return requestedBy; }
    public int requiredApprovals() { return requiredApprovals; }
    public List<Approval> approvals() { return List.copyOf(approvals); }
    public Status status() { return status; }
    public UUID rejectedBy() { return rejectedBy; }
    public Instant rejectedAt() { return rejectedAt; }
    public String rejectionReason() { return rejectionReason; }
    public Instant cancelledAt() { return cancelledAt; }
    public LocalDate settledOn() { return settledOn; }
    public UUID settledBy() { return settledBy; }
    public String paymentMethod() { return paymentMethod; }
    public String paymentReference() { return paymentReference; }
    public UUID settlementEntryId() { return settlementEntryId; }
    public Instant createdAt() { return createdAt; }
    public long version() { return version; }

    /** One official's approval. */
    public record Approval(UUID approverUserId, String note, Instant approvedAt) {
    }
}
