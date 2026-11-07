package ke.chama.modules.penalty.domain.model;

import ke.chama.modules.penalty.domain.exception.PenaltyExceptions.InvalidPenaltyException;
import ke.chama.modules.penalty.domain.exception.PenaltyExceptions.PenaltyClosedException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Money one member owes the chama as a penalty: automatically for a late contribution, or a fine
 * imposed by an official. Paid in one or more payments, or waived (with a reason); never deleted.
 */
public final class Penalty {

    static final int MAX_TEXT_LENGTH = 300;

    public enum Kind {
        LATE_CONTRIBUTION,
        FINE
    }

    public enum Status {
        /** Something is still owed. */
        OUTSTANDING,
        PAID,
        /** The rest was waived. */
        WAIVED
    }

    private final UUID id;
    private final UUID chamaId;
    private final UUID memberUserId;
    private final Kind kind;
    private final String sourceKey;
    private final String description;
    private final long amountMinor;
    private final String currency;
    private final LocalDate assessedOn;
    private final UUID assessedBy;
    private UUID assessmentEntryId;
    private long paidMinor;
    private long waivedMinor;
    private Status status;
    private UUID waivedBy;
    private Instant waivedAt;
    private String waiverReason;
    private UUID waiverEntryId;
    private final Instant createdAt;
    private final long version;

    public Penalty(UUID id, UUID chamaId, UUID memberUserId, Kind kind, String sourceKey, String description,
                   long amountMinor, String currency, LocalDate assessedOn, UUID assessedBy, UUID assessmentEntryId,
                   long paidMinor, long waivedMinor, Status status, UUID waivedBy, Instant waivedAt,
                   String waiverReason, UUID waiverEntryId, Instant createdAt, long version) {
        this.id = Objects.requireNonNull(id);
        this.chamaId = Objects.requireNonNull(chamaId);
        this.memberUserId = Objects.requireNonNull(memberUserId);
        this.kind = kind;
        this.sourceKey = sourceKey;
        this.description = description;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.assessedOn = assessedOn;
        this.assessedBy = assessedBy;
        this.assessmentEntryId = assessmentEntryId;
        this.paidMinor = paidMinor;
        this.waivedMinor = waivedMinor;
        this.status = status;
        this.waivedBy = waivedBy;
        this.waivedAt = waivedAt;
        this.waiverReason = waiverReason;
        this.waiverEntryId = waiverEntryId;
        this.createdAt = createdAt;
        this.version = version;
    }

    /** The source key that makes a late period penalised once per member. */
    public static String lateContributionKey(UUID periodId) {
        return Kind.LATE_CONTRIBUTION + ":" + periodId;
    }

    /**
     * The late penalty for one period: {@code basisPoints} of what is still unpaid (200 = 2%),
     * rounded half up. Empty when it comes to nothing (no rate, or nothing outstanding).
     */
    public static Optional<Penalty> forLateContribution(UUID chamaId, UUID memberUserId, UUID periodId, String periodKey,
                                                        long outstandingMinor, String currency, int basisPoints,
                                                        LocalDate today, Instant now) {
        long amount = BigDecimal.valueOf(outstandingMinor).multiply(BigDecimal.valueOf(basisPoints))
                .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_UP).longValueExact();
        if (amount <= 0) {
            return Optional.empty();
        }
        String text = "Late contribution for %s: %s%% of %s unpaid".formatted(periodKey,
                BigDecimal.valueOf(basisPoints, 2).stripTrailingZeros().toPlainString(),
                formatted(outstandingMinor, currency));
        return Optional.of(new Penalty(UUID.randomUUID(), chamaId, memberUserId, Kind.LATE_CONTRIBUTION,
                lateContributionKey(periodId), text, amount, currency, today, null, null, 0, 0, Status.OUTSTANDING,
                null, null, null, null, now, 0));
    }

    /** A fine an official imposes, e.g. for missing a meeting. */
    public static Penalty fine(UUID chamaId, UUID memberUserId, long amountMinor, String currency, String reason,
                               UUID imposedBy, LocalDate today, Instant now) {
        if (amountMinor <= 0) {
            throw new InvalidPenaltyException("A fine must be greater than zero");
        }
        String text = reason == null ? "" : reason.trim();
        if (text.isEmpty() || text.length() > MAX_TEXT_LENGTH) {
            throw new InvalidPenaltyException("A reason is required (at most " + MAX_TEXT_LENGTH + " characters)");
        }
        return new Penalty(UUID.randomUUID(), chamaId, memberUserId, Kind.FINE, null, text, amountMinor, currency,
                today, imposedBy, null, 0, 0, Status.OUTSTANDING, null, null, null, null, now, 0);
    }

    /** The source key of a penalty brought forward from earlier records: one per member. */
    public static final String BROUGHT_FORWARD_KEY = "BROUGHT_FORWARD";

    /** Penalties and fines a member still owed in the chama's earlier records, as one penalty. */
    public static Penalty broughtForward(UUID chamaId, UUID memberUserId, long amountMinor, String currency,
                                         LocalDate asOf, UUID importedBy, UUID openingEntryId, Instant now) {
        if (amountMinor <= 0) {
            throw new InvalidPenaltyException("A penalty brought forward must be greater than zero");
        }
        Penalty penalty = new Penalty(UUID.randomUUID(), chamaId, memberUserId, Kind.FINE, BROUGHT_FORWARD_KEY,
                "Penalties brought forward from the chama's records as at " + asOf, amountMinor, currency, asOf,
                importedBy, null, 0, 0, Status.OUTSTANDING, null, null, null, null, now, 0);
        penalty.postedAs(openingEntryId);
        return penalty;
    }

    public void postedAs(UUID entryId) {
        this.assessmentEntryId = Objects.requireNonNull(entryId);
    }

    /** A payment towards this penalty: at most what is outstanding. */
    public void pay(long amountMinor) {
        requireOutstanding();
        if (amountMinor <= 0) {
            throw new InvalidPenaltyException("The amount must be greater than zero");
        }
        if (amountMinor > outstandingMinor()) {
            throw new InvalidPenaltyException("Only " + formatted(outstandingMinor(), currency) + " is outstanding on this penalty");
        }
        paidMinor += amountMinor;
        if (outstandingMinor() == 0) {
            status = Status.PAID;
        }
    }

    /** Waives whatever is still outstanding. */
    public long waive(UUID by, String reason, Instant now) {
        requireOutstanding();
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty() || why.length() > MAX_TEXT_LENGTH) {
            throw new InvalidPenaltyException("A reason is required to waive a penalty (at most " + MAX_TEXT_LENGTH + " characters)");
        }
        long waived = outstandingMinor();
        waivedMinor += waived;
        status = Status.WAIVED;
        waivedBy = by;
        waivedAt = now;
        waiverReason = why;
        return waived;
    }

    public void waiverPostedAs(UUID entryId) {
        this.waiverEntryId = entryId;
    }

    public long outstandingMinor() {
        return amountMinor - paidMinor - waivedMinor;
    }

    private void requireOutstanding() {
        if (status != Status.OUTSTANDING) {
            throw new PenaltyClosedException(status);
        }
    }

    static String formatted(long minor, String currency) {
        int digits = Math.max(java.util.Currency.getInstance(currency).getDefaultFractionDigits(), 0);
        return currency + " " + new java.text.DecimalFormat(digits == 0 ? "#,##0" : "#,##0." + "0".repeat(digits))
                .format(BigDecimal.valueOf(minor, digits));
    }

    public UUID id() { return id; }
    public UUID chamaId() { return chamaId; }
    public UUID memberUserId() { return memberUserId; }
    public Kind kind() { return kind; }
    public String sourceKey() { return sourceKey; }
    public String description() { return description; }
    public long amountMinor() { return amountMinor; }
    public String currency() { return currency; }
    public LocalDate assessedOn() { return assessedOn; }
    public UUID assessedBy() { return assessedBy; }
    public UUID assessmentEntryId() { return assessmentEntryId; }
    public long paidMinor() { return paidMinor; }
    public long waivedMinor() { return waivedMinor; }
    public Status status() { return status; }
    public UUID waivedBy() { return waivedBy; }
    public Instant waivedAt() { return waivedAt; }
    public String waiverReason() { return waiverReason; }
    public UUID waiverEntryId() { return waiverEntryId; }
    public Instant createdAt() { return createdAt; }
    public long version() { return version; }
}
