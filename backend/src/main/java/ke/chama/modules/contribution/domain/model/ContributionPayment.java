package ke.chama.modules.contribution.domain.model;

import ke.chama.modules.contribution.domain.exception.ContributionExceptions.InvalidPaymentException;
import ke.chama.modules.contribution.domain.exception.ContributionExceptions.PaymentAlreadyReversedException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Money received from a member towards their contributions. Posted to the ledger when recorded;
 * a mistake is reversed (which reverses the ledger entry too), never edited or deleted.
 */
public final class ContributionPayment {

    static final int MAX_REFERENCE_LENGTH = 100;
    static final int MAX_NOTES_LENGTH = 300;

    public enum Status {
        RECORDED,
        REVERSED
    }

    private final UUID id;
    private final UUID chamaId;
    private final UUID memberUserId;
    private final long amountMinor;
    private final String currency;
    private final PaymentMethod method;
    private final String reference;
    private final LocalDate paidOn;
    private final String notes;
    private final UUID recordedBy;
    private final Instant recordedAt;
    private UUID ledgerEntryId;
    private Status status;
    private UUID reversedBy;
    private Instant reversedAt;
    private String reversalReason;
    private UUID reversalEntryId;
    private final long version;

    public ContributionPayment(UUID id, UUID chamaId, UUID memberUserId, long amountMinor, String currency,
                               PaymentMethod method, String reference, LocalDate paidOn, String notes,
                               UUID recordedBy, Instant recordedAt, UUID ledgerEntryId, Status status,
                               UUID reversedBy, Instant reversedAt, String reversalReason, UUID reversalEntryId,
                               long version) {
        this.id = Objects.requireNonNull(id);
        this.chamaId = Objects.requireNonNull(chamaId);
        this.memberUserId = Objects.requireNonNull(memberUserId);
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.method = method;
        this.reference = reference;
        this.paidOn = paidOn;
        this.notes = notes;
        this.recordedBy = recordedBy;
        this.recordedAt = recordedAt;
        this.ledgerEntryId = ledgerEntryId;
        this.status = status;
        this.reversedBy = reversedBy;
        this.reversedAt = reversedAt;
        this.reversalReason = reversalReason;
        this.reversalEntryId = reversalEntryId;
        this.version = version;
    }

    /**
     * Validates a payment about to be recorded. M-Pesa and bank payments need their reference
     * (transaction code, slip number), so the same money cannot be recorded twice.
     *
     * @param today the chama's current date: a payment cannot be dated in the future
     */
    public static ContributionPayment record(UUID chamaId, UUID memberUserId, long amountMinor, String currency,
                                             PaymentMethod method, String reference, LocalDate paidOn, String notes,
                                             UUID recordedBy, LocalDate today, Instant now) {
        if (amountMinor <= 0) {
            throw new InvalidPaymentException("The amount must be greater than zero");
        }
        if (method == null) {
            throw new InvalidPaymentException("The payment method is required");
        }
        LocalDate date = paidOn == null ? today : paidOn;
        if (date.isAfter(today)) {
            throw new InvalidPaymentException("A payment cannot be dated in the future");
        }
        String ref = reference == null || reference.isBlank() ? null : reference.trim().toUpperCase(Locale.ROOT);
        if (ref == null && method != PaymentMethod.CASH) {
            throw new InvalidPaymentException(method + " payments need their reference (transaction code or slip number)");
        }
        if (ref != null && ref.length() > MAX_REFERENCE_LENGTH) {
            throw new InvalidPaymentException("reference must be at most " + MAX_REFERENCE_LENGTH + " characters");
        }
        String note = notes == null || notes.isBlank() ? null : notes.trim();
        if (note != null && note.length() > MAX_NOTES_LENGTH) {
            throw new InvalidPaymentException("notes must be at most " + MAX_NOTES_LENGTH + " characters");
        }
        return new ContributionPayment(UUID.randomUUID(), chamaId, memberUserId, amountMinor, currency, method, ref,
                date, note, recordedBy, now, null, Status.RECORDED, null, null, null, null, 0);
    }

    public void postedAs(UUID entryId) {
        this.ledgerEntryId = Objects.requireNonNull(entryId);
    }

    public void reverse(UUID by, String reason, UUID entryId, Instant now) {
        if (status == Status.REVERSED) {
            throw new PaymentAlreadyReversedException();
        }
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty()) {
            throw new InvalidPaymentException("A reason is required to reverse a payment");
        }
        this.status = Status.REVERSED;
        this.reversedBy = by;
        this.reversedAt = now;
        this.reversalReason = why.length() > MAX_NOTES_LENGTH ? why.substring(0, MAX_NOTES_LENGTH) : why;
        this.reversalEntryId = entryId;
    }

    public boolean isRecorded() {
        return status == Status.RECORDED;
    }

    public UUID id() { return id; }
    public UUID chamaId() { return chamaId; }
    public UUID memberUserId() { return memberUserId; }
    public long amountMinor() { return amountMinor; }
    public String currency() { return currency; }
    public PaymentMethod method() { return method; }
    public String reference() { return reference; }
    public LocalDate paidOn() { return paidOn; }
    public String notes() { return notes; }
    public UUID recordedBy() { return recordedBy; }
    public Instant recordedAt() { return recordedAt; }
    public UUID ledgerEntryId() { return ledgerEntryId; }
    public Status status() { return status; }
    public UUID reversedBy() { return reversedBy; }
    public Instant reversedAt() { return reversedAt; }
    public String reversalReason() { return reversalReason; }
    public UUID reversalEntryId() { return reversalEntryId; }
    public long version() { return version; }
}
