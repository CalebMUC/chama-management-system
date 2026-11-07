package ke.chama.modules.investment.domain.model;

import ke.chama.modules.investment.domain.exception.InvestmentExceptions.AlreadyApprovedException;
import ke.chama.modules.investment.domain.exception.InvestmentExceptions.InvalidInvestmentException;
import ke.chama.modules.investment.domain.exception.InvestmentExceptions.NotTheProposerException;
import ke.chama.modules.investment.domain.exception.InvestmentExceptions.SelfApprovalNotAllowedException;
import ke.chama.modules.investment.domain.exception.InvestmentExceptions.WrongInvestmentStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Currency;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Something the chama puts its money into: a money market fund, a fixed deposit, T-bills, land.
 * <pre>
 * PROPOSED ──(quorum of officials other than the proposer)──► APPROVED ──(funded)──► ACTIVE ──(closed)──► CLOSED
 *    └──────────── rejected by an approver / cancelled by the proposer ───┘
 * </pre>
 * The books carry an investment at cost: its <em>book value</em> is what was put in (the funding
 * and later deposits), plus reinvested income, less withdrawals. Valuations record what it is worth
 * now without touching the books; the difference only becomes a gain or loss when the investment is
 * closed. Money moving in or out after a valuation moves the latest valuation by the same amount, so
 * a deposit or withdrawal never looks like a gain or loss.
 */
public final class Investment {

    static final int MAX_TEXT_LENGTH = 300;
    static final int MAX_NAME_LENGTH = 120;

    public enum Type {
        MONEY_MARKET_FUND,
        FIXED_DEPOSIT,
        TREASURY_BILL,
        TREASURY_BOND,
        SACCO_SHARES,
        EQUITIES,
        LAND,
        BUSINESS,
        OTHER
    }

    public enum Status {
        PROPOSED,
        APPROVED,
        ACTIVE,
        CLOSED,
        REJECTED,
        CANCELLED
    }

    private static final Set<Status> UNFUNDED = EnumSet.of(Status.PROPOSED, Status.APPROVED);

    private final UUID id;
    private final UUID chamaId;
    private final String name;
    private final Type type;
    private final String institution;
    private final String description;
    private final long amountMinor;
    private final String currency;
    private final Integer expectedReturnBasisPoints;
    private final LocalDate maturityDate;
    private final UUID proposedBy;
    private final int requiredApprovals;
    private final List<Approval> approvals;
    private Status status;
    private UUID rejectedBy;
    private Instant rejectedAt;
    private String rejectionReason;
    private Instant cancelledAt;
    private LocalDate fundedOn;
    private long bookValueMinor;
    private long incomeMinor;
    private long withdrawnMinor;
    private long depositedMinor;
    private Long latestValuationMinor;
    private LocalDate latestValuationOn;
    private LocalDate closedOn;
    private Long closingProceedsMinor;
    private Long realisedGainMinor;
    private final Instant createdAt;
    private final long version;

    public Investment(UUID id, UUID chamaId, String name, Type type, String institution, String description,
                      long amountMinor, String currency, Integer expectedReturnBasisPoints, LocalDate maturityDate,
                      UUID proposedBy, int requiredApprovals, List<Approval> approvals, Status status, UUID rejectedBy,
                      Instant rejectedAt, String rejectionReason, Instant cancelledAt, LocalDate fundedOn,
                      long bookValueMinor, long incomeMinor, long withdrawnMinor, long depositedMinor,
                      Long latestValuationMinor,
                      LocalDate latestValuationOn, LocalDate closedOn, Long closingProceedsMinor, Long realisedGainMinor,
                      Instant createdAt, long version) {
        this.id = Objects.requireNonNull(id);
        this.chamaId = Objects.requireNonNull(chamaId);
        this.name = name;
        this.type = type;
        this.institution = institution;
        this.description = description;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.expectedReturnBasisPoints = expectedReturnBasisPoints;
        this.maturityDate = maturityDate;
        this.proposedBy = Objects.requireNonNull(proposedBy);
        this.requiredApprovals = requiredApprovals;
        this.approvals = new ArrayList<>(approvals);
        this.status = status;
        this.rejectedBy = rejectedBy;
        this.rejectedAt = rejectedAt;
        this.rejectionReason = rejectionReason;
        this.cancelledAt = cancelledAt;
        this.fundedOn = fundedOn;
        this.bookValueMinor = bookValueMinor;
        this.incomeMinor = incomeMinor;
        this.withdrawnMinor = withdrawnMinor;
        this.depositedMinor = depositedMinor;
        this.latestValuationMinor = latestValuationMinor;
        this.latestValuationOn = latestValuationOn;
        this.closedOn = closedOn;
        this.closingProceedsMinor = closingProceedsMinor;
        this.realisedGainMinor = realisedGainMinor;
        this.createdAt = createdAt;
        this.version = version;
    }

    /**
     * A proposal awaiting approval.
     *
     * @param expectedReturnBasisPoints the expected yearly return (1250 = 12.5%); optional
     * @param maturityDate              when it matures, for fixed-term investments; optional, after today
     * @param requiredApprovals         the chama's approval quorum when it was proposed
     */
    public static Investment propose(UUID chamaId, UUID proposedBy, String name, Type type, String institution,
                                     String description, long amountMinor, String currency,
                                     Integer expectedReturnBasisPoints, LocalDate maturityDate, int requiredApprovals,
                                     LocalDate today, Instant now) {
        if (type == null) {
            throw new InvalidInvestmentException("A type is required");
        }
        if (amountMinor <= 0) {
            throw new InvalidInvestmentException("The amount must be greater than zero");
        }
        if (expectedReturnBasisPoints != null && (expectedReturnBasisPoints < 0 || expectedReturnBasisPoints > 100_000)) {
            throw new InvalidInvestmentException("The expected return must be between 0% and 1000%");
        }
        if (maturityDate != null && !maturityDate.isAfter(today)) {
            throw new InvalidInvestmentException("The maturity date must be in the future");
        }
        return new Investment(UUID.randomUUID(), chamaId, text(name, MAX_NAME_LENGTH, "name"), type,
                optional(institution, MAX_NAME_LENGTH, "institution"), optional(description, MAX_TEXT_LENGTH, "description"),
                amountMinor, currency, expectedReturnBasisPoints, maturityDate, proposedBy, Math.max(requiredApprovals, 1),
                List.of(), Status.PROPOSED, null, null, null, null, null, 0, 0, 0, 0, null, null, null, null, null, now, 0);
    }

    // ---- decisions ------------------------------------------------------------------------------

    public void approve(UUID approverUserId, String note, Instant now) {
        requireNotProposer(approverUserId);
        requireStatus("approve", Status.PROPOSED);
        if (approvals.stream().anyMatch(a -> a.approverUserId().equals(approverUserId))) {
            throw new AlreadyApprovedException();
        }
        approvals.add(new Approval(approverUserId, optional(note, MAX_TEXT_LENGTH, "note"), now));
        if (approvals.size() >= requiredApprovals) {
            status = Status.APPROVED;
        }
    }

    public void reject(UUID officialUserId, String reason, Instant now) {
        requireNotProposer(officialUserId);
        requireUnfunded("reject");
        rejectionReason = text(reason, MAX_TEXT_LENGTH, "reason");
        status = Status.REJECTED;
        rejectedBy = officialUserId;
        rejectedAt = now;
    }

    public void cancel(UUID actorUserId, Instant now) {
        if (!proposedBy.equals(actorUserId)) {
            throw new NotTheProposerException();
        }
        requireUnfunded("cancel");
        status = Status.CANCELLED;
        cancelledAt = now;
    }

    // ---- money ----------------------------------------------------------------------------------

    /** Puts the approved amount in. Returns the transaction to post. */
    public InvestmentTransaction fund(UUID by, String method, String reference, LocalDate date, LocalDate today, Instant now) {
        requireStatus("fund", Status.APPROVED);
        InvestmentTransaction funding = InvestmentTransaction.record(this, InvestmentTransaction.Kind.FUNDING, amountMinor,
                method, reference, date, null, by, today, now);
        status = Status.ACTIVE;
        fundedOn = funding.date();
        bookValueMinor = amountMinor;
        return funding;
    }

    /**
     * Interest, dividends or rent received. REINVESTED income stays in the investment and adds to
     * its book value; otherwise it is paid into cash, bank or M-Pesa.
     */
    public InvestmentTransaction recordIncome(UUID by, long amount, String method, String reference, LocalDate date,
                                              String note, LocalDate today, Instant now) {
        requireStatus("record income on", Status.ACTIVE);
        requirePositive(amount);
        InvestmentTransaction income = InvestmentTransaction.record(this, InvestmentTransaction.Kind.INCOME, amount,
                method, reference, date, note, by, today, now);
        incomeMinor += amount;
        if (income.reinvested()) {
            bookValueMinor += amount;
            moveValuation(amount);
        }
        return income;
    }

    /**
     * Puts more money into an active investment, e.g. a monthly top-up of a money market fund. No
     * new approval: the investment was approved, and the money stays the chama's.
     */
    public InvestmentTransaction deposit(UUID by, long amount, String method, String reference, LocalDate date,
                                         String note, LocalDate today, Instant now) {
        requireStatus("deposit into", Status.ACTIVE);
        requirePositive(amount);
        InvestmentTransaction deposit = InvestmentTransaction.record(this, InvestmentTransaction.Kind.DEPOSIT, amount,
                method, reference, date, note, by, today, now);
        requireNotReinvested(deposit);
        bookValueMinor += amount;
        depositedMinor += amount;
        moveValuation(amount);
        return deposit;
    }

    /** Takes part of the money out. To take everything out, close the investment instead. */
    public InvestmentTransaction withdraw(UUID by, long amount, String method, String reference, LocalDate date,
                                          String note, LocalDate today, Instant now) {
        requireStatus("withdraw from", Status.ACTIVE);
        requirePositive(amount);
        if (amount >= bookValueMinor) {
            throw new InvalidInvestmentException("A withdrawal must be less than the book value of "
                    + formatted(bookValueMinor, currency) + "; close the investment to take everything out");
        }
        InvestmentTransaction withdrawal = InvestmentTransaction.record(this, InvestmentTransaction.Kind.WITHDRAWAL,
                amount, method, reference, date, note, by, today, now);
        requireNotReinvested(withdrawal);
        bookValueMinor -= amount;
        withdrawnMinor += amount;
        moveValuation(-amount);
        return withdrawal;
    }

    /**
     * Sells or redeems the investment for {@code proceeds}. The difference from the book value is
     * the realised gain (or, when negative, loss).
     */
    public InvestmentTransaction close(UUID by, long proceeds, String method, String reference, LocalDate date,
                                       String note, LocalDate today, Instant now) {
        requireStatus("close", Status.ACTIVE);
        if (proceeds < 0) {
            throw new InvalidInvestmentException("The proceeds cannot be negative");
        }
        InvestmentTransaction closure = InvestmentTransaction.record(this, InvestmentTransaction.Kind.CLOSURE, proceeds,
                method, reference, date, note, by, today, now);
        requireNotReinvested(closure);
        closingProceedsMinor = proceeds;
        realisedGainMinor = proceeds - bookValueMinor;
        closedOn = closure.date();
        bookValueMinor = 0;
        status = Status.CLOSED;
        return closure;
    }

    /** What the investment is worth now. Informational: the books keep it at cost until it is closed. */
    public Valuation value(UUID by, long valueMinor, LocalDate date, String note, LocalDate today, Instant now) {
        requireStatus("value", Status.ACTIVE);
        if (valueMinor < 0) {
            throw new InvalidInvestmentException("A valuation cannot be negative");
        }
        LocalDate on = date == null ? today : date;
        if (on.isAfter(today) || on.isBefore(fundedOn)) {
            throw new InvalidInvestmentException("A valuation must be dated between the funding date and today");
        }
        if (latestValuationOn == null || !on.isBefore(latestValuationOn)) {
            latestValuationMinor = valueMinor;
            latestValuationOn = on;
        }
        return new Valuation(UUID.randomUUID(), chamaId, id, valueMinor, on, optional(note, MAX_TEXT_LENGTH, "note"),
                by, now);
    }

    /** What it is worth: the latest valuation, or the book value if never valued. */
    public long marketValueMinor() {
        return latestValuationMinor != null ? latestValuationMinor : bookValueMinor;
    }

    /** Latest valuation less book value; null if never valued or not active. */
    public Long unrealisedGainMinor() {
        return status == Status.ACTIVE && latestValuationMinor != null ? latestValuationMinor - bookValueMinor : null;
    }

    /** Everything put in: the funding plus later deposits. Zero until funded. */
    public long investedMinor() {
        return fundedOn == null ? 0 : amountMinor + depositedMinor;
    }

    /**
     * How the investment is doing, or null until it is funded. Profit is income less the expenses
     * charged to it, plus its gain in value: realised when closed; otherwise the latest valuation
     * less the book value (zero if never valued).
     *
     * @param expensesMinor paid expenses charged to this investment (e.g. an Airbnb's rent and bills)
     */
    public Performance performance(long expensesMinor, LocalDate today) {
        if (fundedOn == null) {
            return null;
        }
        boolean closed = status == Status.CLOSED;
        long gain = closed ? realisedGainMinor : latestValuationMinor == null ? 0 : latestValuationMinor - bookValueMinor;
        long totalReturn = incomeMinor - expensesMinor + gain;
        long invested = investedMinor();
        int returnBp = invested == 0 ? 0 : BigDecimal.valueOf(totalReturn).multiply(BigDecimal.valueOf(10_000))
                .divide(BigDecimal.valueOf(invested), 0, RoundingMode.HALF_UP).intValueExact();
        long days = ChronoUnit.DAYS.between(fundedOn, closed ? closedOn : today);
        Integer annualised = days < Performance.MIN_DAYS_TO_ANNUALISE ? null
                : BigDecimal.valueOf(returnBp).multiply(BigDecimal.valueOf(365))
                .divide(BigDecimal.valueOf(days), 0, RoundingMode.HALF_UP).intValueExact();
        return new Performance(invested, incomeMinor, expensesMinor, incomeMinor - expensesMinor, gain, closed,
                totalReturn, returnBp, annualised, days, totalReturn > 0);
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** Money moved in (+) or out (-) of a valued investment changes its value by the same amount. */
    private void moveValuation(long amount) {
        if (latestValuationMinor != null) {
            latestValuationMinor = Math.max(latestValuationMinor + amount, 0);
        }
    }

    private static void requirePositive(long amount) {
        if (amount <= 0) {
            throw new InvalidInvestmentException("The amount must be greater than zero");
        }
    }

    private static void requireNotReinvested(InvestmentTransaction transaction) {
        if (transaction.reinvested()) {
            throw new InvalidInvestmentException("Only income can be REINVESTED; money taken out goes to CASH, BANK or MPESA");
        }
    }

    private void requireNotProposer(UUID officialUserId) {
        if (proposedBy.equals(officialUserId)) {
            throw new SelfApprovalNotAllowedException();
        }
    }

    private void requireStatus(String action, Status required) {
        if (status != required) {
            throw new WrongInvestmentStatusException(action, status);
        }
    }

    private void requireUnfunded(String action) {
        if (!UNFUNDED.contains(status)) {
            throw new WrongInvestmentStatusException(action, status);
        }
    }

    static String text(String value, int max, String field) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) {
            throw new InvalidInvestmentException("A " + field + " is required");
        }
        if (v.length() > max) {
            throw new InvalidInvestmentException("The " + field + " must be at most " + max + " characters");
        }
        return v;
    }

    static String optional(String value, int max, String field) {
        return value == null || value.isBlank() ? null : text(value, max, field);
    }

    public UUID id() { return id; }
    public UUID chamaId() { return chamaId; }
    public String name() { return name; }
    public Type type() { return type; }
    public String institution() { return institution; }
    public String description() { return description; }
    public long amountMinor() { return amountMinor; }
    public String currency() { return currency; }
    public Integer expectedReturnBasisPoints() { return expectedReturnBasisPoints; }
    public LocalDate maturityDate() { return maturityDate; }
    public UUID proposedBy() { return proposedBy; }
    public int requiredApprovals() { return requiredApprovals; }
    public List<Approval> approvals() { return List.copyOf(approvals); }
    public Status status() { return status; }
    public UUID rejectedBy() { return rejectedBy; }
    public Instant rejectedAt() { return rejectedAt; }
    public String rejectionReason() { return rejectionReason; }
    public Instant cancelledAt() { return cancelledAt; }
    public LocalDate fundedOn() { return fundedOn; }
    public long bookValueMinor() { return bookValueMinor; }
    public long incomeMinor() { return incomeMinor; }
    public long withdrawnMinor() { return withdrawnMinor; }
    public long depositedMinor() { return depositedMinor; }
    public Long latestValuationMinor() { return latestValuationMinor; }
    public LocalDate latestValuationOn() { return latestValuationOn; }
    public LocalDate closedOn() { return closedOn; }
    public Long closingProceedsMinor() { return closingProceedsMinor; }
    public Long realisedGainMinor() { return realisedGainMinor; }
    public Instant createdAt() { return createdAt; }
    public long version() { return version; }

    /** One official's approval. */
    public record Approval(UUID approverUserId, String note, Instant approvedAt) {
    }

    /**
     * How a funded investment is doing. Amounts are minor units; returns are basis points (1250 = 12.5%).
     *
     * @param investedMinor               everything put in: funding plus deposits
     * @param incomeMinor                 interest, dividends, rent or bookings received (paid out or reinvested)
     * @param expensesMinor               paid expenses charged to the investment
     * @param netIncomeMinor              income less expenses
     * @param gainMinor                   realised gain once closed; otherwise latest valuation less book value
     * @param gainRealised                whether the gain is final (the investment is closed)
     * @param totalReturnMinor            net income plus gain: the profit (negative for a loss)
     * @param returnBasisPoints           total return as a share of what was put in
     * @param annualisedReturnBasisPoints the return per year held (simple, not compounded); null under 30 days
     * @param daysHeld                    from funding to closure or today
     * @param profitable                  total return above zero
     */
    public record Performance(long investedMinor,
                              long incomeMinor,
                              long expensesMinor,
                              long netIncomeMinor,
                              long gainMinor,
                              boolean gainRealised,
                              long totalReturnMinor,
                              int returnBasisPoints,
                              Integer annualisedReturnBasisPoints,
                              long daysHeld,
                              boolean profitable) {

        static final int MIN_DAYS_TO_ANNUALISE = 30;
    }

    /** What the investment was worth on a date. */
    public record Valuation(UUID id, UUID chamaId, UUID investmentId, long valueMinor, LocalDate valuedOn, String note,
                            UUID recordedBy, Instant recordedAt) {
    }

    /** E.g. "KES 5,000.00". */
    static String formatted(long minor, String currency) {
        int digits = Math.max(Currency.getInstance(currency).getDefaultFractionDigits(), 0);
        return currency + " " + new DecimalFormat(digits == 0 ? "#,##0" : "#,##0." + "0".repeat(digits))
                .format(BigDecimal.valueOf(minor, digits));
    }
}
