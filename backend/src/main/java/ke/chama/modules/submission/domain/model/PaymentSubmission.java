package ke.chama.modules.submission.domain.model;

import ke.chama.modules.submission.domain.exception.SubmissionExceptions.InvalidSubmissionException;
import ke.chama.modules.submission.domain.exception.SubmissionExceptions.NotTheSubmitterException;
import ke.chama.modules.submission.domain.exception.SubmissionExceptions.SelfReviewNotAllowedException;
import ke.chama.modules.submission.domain.exception.SubmissionExceptions.WrongSubmissionStatusException;
import ke.chama.shared.security.ChamaPermission;
import ke.chama.shared.tenancy.LicenseFeature;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A payment a member says they made: a contribution, a penalty payment or a loan repayment. It
 * moves no money and touches no books until an official approves it; approving records the real
 * payment through the module that owns it. PENDING then APPROVED, REJECTED (with a reason) or
 * CANCELLED (by the member).
 */
public class PaymentSubmission {

    public static final int MAX_REFERENCE_LENGTH = 100;
    public static final int MAX_TEXT_LENGTH = 300;
    static final Set<String> METHODS = Set.of("CASH", "BANK", "MPESA");

    public enum Kind {
        CONTRIBUTION(ChamaPermission.CONTRIBUTION_RECORD, LicenseFeature.CONTRIBUTIONS),
        PENALTY_PAYMENT(ChamaPermission.PENALTY_RECORD, LicenseFeature.PENALTIES),
        LOAN_REPAYMENT(ChamaPermission.LOAN_REPAYMENT_RECORD, LicenseFeature.LOANS);

        private final ChamaPermission reviewPermission;
        private final LicenseFeature feature;

        Kind(ChamaPermission reviewPermission, LicenseFeature feature) {
            this.reviewPermission = reviewPermission;
            this.feature = feature;
        }

        /** The permission an official needs to approve or reject this kind of payment. */
        public ChamaPermission reviewPermission() {
            return reviewPermission;
        }

        public LicenseFeature feature() {
            return feature;
        }

        /** A penalty payment names its penalty and a repayment its loan; a contribution names nothing. */
        public boolean needsTarget() {
            return this != CONTRIBUTION;
        }
    }

    public enum Status {
        /** Waiting for an official to check it. */
        PENDING,
        /** Confirmed and recorded as a real payment. */
        APPROVED,
        REJECTED,
        CANCELLED
    }

    private final UUID id;
    private final UUID chamaId;
    private final UUID memberUserId;
    private final Kind kind;
    private final UUID targetId;
    private final long amountMinor;
    private final String currency;
    private final String method;
    private final String reference;
    private final LocalDate paidOn;
    private final String notes;
    private final Instant submittedAt;
    private Status status;
    private UUID reviewedBy;
    private Instant reviewedAt;
    private String reviewNote;
    private Instant cancelledAt;
    private final long version;

    public PaymentSubmission(UUID id, UUID chamaId, UUID memberUserId, Kind kind, UUID targetId, long amountMinor,
                             String currency, String method, String reference, LocalDate paidOn, String notes,
                             Instant submittedAt, Status status, UUID reviewedBy, Instant reviewedAt,
                             String reviewNote, Instant cancelledAt, long version) {
        this.id = id;
        this.chamaId = chamaId;
        this.memberUserId = memberUserId;
        this.kind = kind;
        this.targetId = targetId;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.method = method;
        this.reference = reference;
        this.paidOn = paidOn;
        this.notes = notes;
        this.submittedAt = submittedAt;
        this.status = status;
        this.reviewedBy = reviewedBy;
        this.reviewedAt = reviewedAt;
        this.reviewNote = reviewNote;
        this.cancelledAt = cancelledAt;
        this.version = version;
    }

    /**
     * @param paidOn when the member paid; today when null, never in the future
     */
    public static PaymentSubmission submit(UUID chamaId, UUID memberUserId, Kind kind, UUID targetId, long amountMinor,
                                           String currency, String method, String reference, LocalDate paidOn,
                                           String notes, LocalDate today, Instant now) {
        if (kind == null) {
            throw new InvalidSubmissionException("Say what the payment was for: CONTRIBUTION, PENALTY_PAYMENT or LOAN_REPAYMENT");
        }
        if (kind.needsTarget() && targetId == null) {
            throw new InvalidSubmissionException(kind == Kind.PENALTY_PAYMENT
                    ? "Choose the penalty this payment is for" : "Choose the loan this repayment is for");
        }
        if (!kind.needsTarget() && targetId != null) {
            throw new InvalidSubmissionException("A contribution is not paid towards a penalty or a loan");
        }
        if (amountMinor <= 0) {
            throw new InvalidSubmissionException("The amount must be greater than zero");
        }
        String how = method == null ? null : method.trim().toUpperCase(Locale.ROOT);
        if (how == null || !METHODS.contains(how)) {
            throw new InvalidSubmissionException("The payment method must be CASH, BANK or MPESA");
        }
        String ref = reference == null || reference.isBlank() ? null : reference.trim().toUpperCase(Locale.ROOT);
        if (ref == null && !"CASH".equals(how)) {
            throw new InvalidSubmissionException(how + " payments need their reference (M-Pesa code or bank slip number)");
        }
        if (ref != null && ref.length() > MAX_REFERENCE_LENGTH) {
            throw new InvalidSubmissionException("The reference must be at most " + MAX_REFERENCE_LENGTH + " characters");
        }
        LocalDate date = paidOn == null ? today : paidOn;
        if (date.isAfter(today)) {
            throw new InvalidSubmissionException("A payment cannot be dated in the future");
        }
        return new PaymentSubmission(UUID.randomUUID(), chamaId, memberUserId, kind, targetId, amountMinor, currency,
                how, ref, date, optional(notes, "notes"), now, Status.PENDING, null, null, null, null, 0);
    }

    /** The official confirmed the money arrived; the caller records the real payment. */
    public void approve(UUID officialUserId, String note, Instant now) {
        requirePending("approve");
        requireNotSubmitter(officialUserId);
        this.status = Status.APPROVED;
        this.reviewedBy = officialUserId;
        this.reviewedAt = now;
        this.reviewNote = optional(note, "note");
    }

    public void reject(UUID officialUserId, String reason, Instant now) {
        requirePending("reject");
        requireNotSubmitter(officialUserId);
        String why = optional(reason, "reason");
        if (why == null) {
            throw new InvalidSubmissionException("A reason is required to reject a payment, so the member knows what to fix");
        }
        this.status = Status.REJECTED;
        this.reviewedBy = officialUserId;
        this.reviewedAt = now;
        this.reviewNote = why;
    }

    /** The member withdraws their own report, e.g. it had a typo. */
    public void cancel(UUID actorUserId, Instant now) {
        if (!memberUserId.equals(actorUserId)) {
            throw new NotTheSubmitterException();
        }
        requirePending("cancel");
        this.status = Status.CANCELLED;
        this.cancelledAt = now;
    }

    private void requirePending(String action) {
        if (status != Status.PENDING) {
            throw new WrongSubmissionStatusException(action, status);
        }
    }

    private void requireNotSubmitter(UUID officialUserId) {
        if (memberUserId.equals(officialUserId)) {
            throw new SelfReviewNotAllowedException();
        }
    }

    private static String optional(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        if (v.length() > MAX_TEXT_LENGTH) {
            throw new InvalidSubmissionException("The " + field + " must be at most " + MAX_TEXT_LENGTH + " characters");
        }
        return v;
    }

    public UUID id() { return id; }
    public UUID chamaId() { return chamaId; }
    public UUID memberUserId() { return memberUserId; }
    public Kind kind() { return kind; }
    public UUID targetId() { return targetId; }
    public long amountMinor() { return amountMinor; }
    public String currency() { return currency; }
    public String method() { return method; }
    public String reference() { return reference; }
    public LocalDate paidOn() { return paidOn; }
    public String notes() { return notes; }
    public Instant submittedAt() { return submittedAt; }
    public Status status() { return status; }
    public UUID reviewedBy() { return reviewedBy; }
    public Instant reviewedAt() { return reviewedAt; }
    /** The approver's optional note, or the rejection reason. */
    public String reviewNote() { return reviewNote; }
    public Instant cancelledAt() { return cancelledAt; }
    public long version() { return version; }

    @Override
    public boolean equals(Object o) {
        return o instanceof PaymentSubmission other && Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
