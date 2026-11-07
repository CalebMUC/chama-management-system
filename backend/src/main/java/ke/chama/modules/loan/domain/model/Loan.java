package ke.chama.modules.loan.domain.model;

import ke.chama.modules.loan.domain.exception.LoanExceptions.AlreadyApprovedException;
import ke.chama.modules.loan.domain.exception.LoanExceptions.GuarantorAlreadyRespondedException;
import ke.chama.modules.loan.domain.exception.LoanExceptions.GuarantorCannotApproveException;
import ke.chama.modules.loan.domain.exception.LoanExceptions.InvalidLoanException;
import ke.chama.modules.loan.domain.exception.LoanExceptions.NotAGuarantorException;
import ke.chama.modules.loan.domain.exception.LoanExceptions.NotTheBorrowerException;
import ke.chama.modules.loan.domain.exception.LoanExceptions.SelfHandlingNotAllowedException;
import ke.chama.modules.loan.domain.exception.LoanExceptions.WrongLoanStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A member's loan, from application to the last repayment.
 * <pre>
 * PENDING_GUARANTORS ──(enough guarantors accept)──► PENDING_APPROVAL ──(quorum of officials)──► APPROVED
 *        │                                                  │                                       │
 *        └──────────── rejected by an official / cancelled by the borrower ─────────────────────────┤
 *                                                                                              disbursed
 *                                                                                                   ▼
 *                                                                        ACTIVE ──(fully repaid)──► REPAID
 * </pre>
 * Interest is flat: {@code interestBasisPoints} of the principal for the whole term. The borrower
 * repays principal plus interest in {@code termMonths} equal monthly instalments, the first a month
 * after disbursement. Repayments clear the oldest instalment first; paying early is allowed.
 */
public final class Loan {

    public static final int MAX_TERM_MONTHS = 36;
    static final int MAX_TEXT_LENGTH = 300;
    private static final Set<String> METHODS = Set.of("CASH", "BANK", "MPESA");

    public enum Status {
        PENDING_GUARANTORS,
        PENDING_APPROVAL,
        APPROVED,
        ACTIVE,
        REPAID,
        REJECTED,
        CANCELLED;

        /** Not yet decided or not yet paid off: the borrower cannot apply for another loan. */
        public boolean open() {
            return this != REPAID && this != REJECTED && this != CANCELLED;
        }
    }

    private static final Set<Status> UNDISBURSED = EnumSet.of(Status.PENDING_GUARANTORS, Status.PENDING_APPROVAL,
            Status.APPROVED);

    private final UUID id;
    private final UUID chamaId;
    private final UUID borrowerUserId;
    /** The loan product applied for; its terms were copied onto the loan at that moment. */
    private final UUID productId;
    private final long principalMinor;
    private final int interestBasisPoints;
    private final long interestMinor;
    private final int termMonths;
    private final String purpose;
    private final String currency;
    private final LocalDate appliedOn;
    private final int requiredGuarantors;
    private final int requiredApprovals;
    private final List<Guarantor> guarantors;
    private final List<Approval> approvals;
    private Status status;
    private UUID rejectedBy;
    private Instant rejectedAt;
    private String rejectionReason;
    private Instant cancelledAt;
    private LocalDate disbursedOn;
    private UUID disbursedBy;
    private String disbursementMethod;
    private String disbursementReference;
    private UUID disbursementEntryId;
    private long repaidMinor;
    private final Instant createdAt;
    private final long version;

    public Loan(UUID id, UUID chamaId, UUID borrowerUserId, UUID productId, long principalMinor, int interestBasisPoints,
                long interestMinor, int termMonths, String purpose, String currency, LocalDate appliedOn,
                int requiredGuarantors, int requiredApprovals, List<Guarantor> guarantors, List<Approval> approvals,
                Status status, UUID rejectedBy, Instant rejectedAt, String rejectionReason, Instant cancelledAt,
                LocalDate disbursedOn, UUID disbursedBy, String disbursementMethod, String disbursementReference,
                UUID disbursementEntryId, long repaidMinor, Instant createdAt, long version) {
        this.id = Objects.requireNonNull(id);
        this.chamaId = Objects.requireNonNull(chamaId);
        this.borrowerUserId = Objects.requireNonNull(borrowerUserId);
        this.productId = Objects.requireNonNull(productId);
        this.principalMinor = principalMinor;
        this.interestBasisPoints = interestBasisPoints;
        this.interestMinor = interestMinor;
        this.termMonths = termMonths;
        this.purpose = purpose;
        this.currency = currency;
        this.appliedOn = appliedOn;
        this.requiredGuarantors = requiredGuarantors;
        this.requiredApprovals = requiredApprovals;
        this.guarantors = new ArrayList<>(guarantors);
        this.approvals = new ArrayList<>(approvals);
        this.status = status;
        this.rejectedBy = rejectedBy;
        this.rejectedAt = rejectedAt;
        this.rejectionReason = rejectionReason;
        this.cancelledAt = cancelledAt;
        this.disbursedOn = disbursedOn;
        this.disbursedBy = disbursedBy;
        this.disbursementMethod = disbursementMethod;
        this.disbursementReference = disbursementReference;
        this.disbursementEntryId = disbursementEntryId;
        this.repaidMinor = repaidMinor;
        this.createdAt = createdAt;
        this.version = version;
    }

    /**
     * A new application. Eligibility (savings, membership) is checked before this; here the loan
     * itself must make sense and name enough distinct guarantors other than the borrower.
     *
     * @param requiredGuarantors how many guarantors must accept (0 when the loan is small enough)
     * @param requiredApprovals  the chama's approval quorum when the member applied
     */
    public static Loan apply(UUID chamaId, UUID borrowerUserId, UUID productId, long principalMinor, int termMonths, String purpose,
                             String currency, int interestBasisPoints, int requiredGuarantors, int requiredApprovals,
                             List<UUID> guarantorUserIds, LocalDate today, Instant now) {
        if (principalMinor <= 0) {
            throw new InvalidLoanException("The amount must be greater than zero");
        }
        if (termMonths < 1 || termMonths > MAX_TERM_MONTHS) {
            throw new InvalidLoanException("The term must be between 1 and " + MAX_TERM_MONTHS + " months");
        }
        String why = purpose == null ? "" : purpose.trim();
        if (why.isEmpty() || why.length() > MAX_TEXT_LENGTH) {
            throw new InvalidLoanException("A purpose is required (at most " + MAX_TEXT_LENGTH + " characters)");
        }
        List<UUID> named = guarantorUserIds == null ? List.of() : guarantorUserIds;
        if (new HashSet<>(named).size() != named.size()) {
            throw new InvalidLoanException("Each guarantor can be named only once");
        }
        if (named.contains(borrowerUserId)) {
            throw new InvalidLoanException("You cannot guarantee your own loan");
        }
        if (requiredGuarantors == 0 && !named.isEmpty()) {
            throw new InvalidLoanException("A loan of this size does not need guarantors");
        }
        if (named.size() < requiredGuarantors) {
            throw new InvalidLoanException("A loan of this size needs " + requiredGuarantors + " guarantor"
                    + (requiredGuarantors == 1 ? "" : "s") + " from the chama's active members");
        }
        long interest = BigDecimal.valueOf(principalMinor).multiply(BigDecimal.valueOf(interestBasisPoints))
                .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_UP).longValueExact();
        List<Guarantor> guarantors = named.stream().map(g -> Guarantor.requested(g, now)).toList();
        return new Loan(UUID.randomUUID(), chamaId, borrowerUserId, productId, principalMinor, interestBasisPoints, interest,
                termMonths, why, currency, today, requiredGuarantors, Math.max(requiredApprovals, 1), guarantors,
                List.of(), requiredGuarantors > 0 ? Status.PENDING_GUARANTORS : Status.PENDING_APPROVAL, null, null,
                null, null, null, null, null, null, null, 0, now, 0);
    }

    /**
     * A loan brought over from the chama's earlier records: already disbursed and partly repaid.
     * Its repayment schedule continues from {@code disbursedOn}; the opening-balance entry is its
     * disbursement entry.
     */
    public static Loan imported(UUID chamaId, UUID borrowerUserId, UUID productId, long principalMinor, long interestMinor,
                                int termMonths, LocalDate disbursedOn, long repaidMinor, String purpose, String currency,
                                UUID importedBy, UUID openingEntryId, Instant now) {
        if (principalMinor <= 0 || interestMinor < 0) {
            throw new InvalidLoanException("The principal must be greater than zero and the interest not negative");
        }
        if (termMonths < 1 || termMonths > MAX_TERM_MONTHS) {
            throw new InvalidLoanException("The term must be between 1 and " + MAX_TERM_MONTHS + " months");
        }
        if (repaidMinor < 0 || repaidMinor >= principalMinor + interestMinor) {
            throw new InvalidLoanException("What was repaid must be less than principal plus interest");
        }
        String why = purpose == null || purpose.isBlank() ? "Loan brought forward" : purpose.trim();
        if (why.length() > MAX_TEXT_LENGTH) {
            throw new InvalidLoanException("The purpose must be at most " + MAX_TEXT_LENGTH + " characters");
        }
        int interestBp = BigDecimal.valueOf(interestMinor).multiply(BigDecimal.valueOf(10_000))
                .divide(BigDecimal.valueOf(principalMinor), 0, RoundingMode.HALF_UP).intValueExact();
        return new Loan(UUID.randomUUID(), chamaId, borrowerUserId, productId, principalMinor, interestBp, interestMinor, termMonths,
                why, currency, disbursedOn, 0, 1, List.of(), List.of(), Status.ACTIVE, null, null, null, null, disbursedOn,
                importedBy, null, null, Objects.requireNonNull(openingEntryId), repaidMinor, now, 0);
    }

    // ---- guarantors -----------------------------------------------------------------------------

    /** The borrower names another guarantor, e.g. after one declined. */
    public Guarantor addGuarantor(UUID actorUserId, UUID guarantorUserId, Instant now) {
        requireBorrower(actorUserId);
        requireStatus("add a guarantor to", Status.PENDING_GUARANTORS);
        if (guarantorUserId.equals(borrowerUserId)) {
            throw new InvalidLoanException("You cannot guarantee your own loan");
        }
        if (guarantor(guarantorUserId).isPresent()) {
            throw new InvalidLoanException("That member has already been asked to guarantee this loan");
        }
        Guarantor added = Guarantor.requested(guarantorUserId, now);
        guarantors.add(added);
        return added;
    }

    /** A guarantor accepts or declines. Once enough accept, the loan goes to the officials for approval. */
    public void respondAsGuarantor(UUID guarantorUserId, boolean accept, Instant now) {
        Guarantor current = guarantor(guarantorUserId).orElseThrow(NotAGuarantorException::new);
        if (current.status() != Guarantor.Status.REQUESTED) {
            throw new GuarantorAlreadyRespondedException();
        }
        requireStatus("respond to a guarantee request for", Status.PENDING_GUARANTORS);
        replace(current.respond(accept ? Guarantor.Status.ACCEPTED : Guarantor.Status.DECLINED, now));
        if (acceptedGuarantors() >= requiredGuarantors) {
            withdrawPendingRequests(now);
            status = Status.PENDING_APPROVAL;
        }
    }

    // ---- decisions ------------------------------------------------------------------------------

    /** One official's approval. The loan is approved once {@code requiredApprovals} distinct officials approve. */
    public void approve(UUID approverUserId, String note, Instant now) {
        requireNotBorrower(approverUserId);
        requireStatus("approve", Status.PENDING_APPROVAL);
        if (guarantor(approverUserId).filter(g -> g.status() == Guarantor.Status.ACCEPTED).isPresent()) {
            throw new GuarantorCannotApproveException();
        }
        if (approvals.stream().anyMatch(a -> a.approverUserId().equals(approverUserId))) {
            throw new AlreadyApprovedException();
        }
        String text = note == null || note.isBlank() ? null : note.trim();
        if (text != null && text.length() > MAX_TEXT_LENGTH) {
            throw new InvalidLoanException("The note must be at most " + MAX_TEXT_LENGTH + " characters");
        }
        approvals.add(new Approval(approverUserId, text, now));
        if (approvals.size() >= requiredApprovals) {
            status = Status.APPROVED;
        }
    }

    /** Any one official can turn the loan down before it is disbursed. A reason is required. */
    public void reject(UUID officialUserId, String reason, Instant now) {
        requireNotBorrower(officialUserId);
        requireUndisbursed("reject");
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty() || why.length() > MAX_TEXT_LENGTH) {
            throw new InvalidLoanException("A reason is required to reject a loan (at most " + MAX_TEXT_LENGTH + " characters)");
        }
        status = Status.REJECTED;
        rejectedBy = officialUserId;
        rejectedAt = now;
        rejectionReason = why;
        withdrawPendingRequests(now);
    }

    /** The borrower withdraws the application before the money is paid out. */
    public void cancel(UUID actorUserId, Instant now) {
        requireBorrower(actorUserId);
        requireUndisbursed("cancel");
        status = Status.CANCELLED;
        cancelledAt = now;
        withdrawPendingRequests(now);
    }

    // ---- money ----------------------------------------------------------------------------------

    /** Pays the principal out to the borrower. The caller posts it to the ledger and records the entry. */
    public void disburse(UUID officialUserId, String method, String reference, LocalDate date, LocalDate today) {
        requireNotBorrower(officialUserId);
        requireStatus("disburse", Status.APPROVED);
        String ref = validPayment(method, reference, date, today);
        if (date != null && date.isBefore(appliedOn)) {
            throw new InvalidLoanException("A loan cannot be disbursed before it was applied for");
        }
        status = Status.ACTIVE;
        disbursedOn = date == null ? today : date;
        disbursedBy = officialUserId;
        disbursementMethod = method;
        disbursementReference = ref;
    }

    public void disbursementPostedAs(UUID entryId) {
        this.disbursementEntryId = Objects.requireNonNull(entryId);
    }

    /** A repayment of at most what is outstanding; the last one closes the loan. */
    public void repay(UUID officialUserId, long amountMinor) {
        requireNotBorrower(officialUserId);
        requireStatus("record a repayment on", Status.ACTIVE);
        if (amountMinor <= 0) {
            throw new InvalidLoanException("The amount must be greater than zero");
        }
        if (amountMinor > outstandingMinor()) {
            throw new InvalidLoanException("Only " + formatted(outstandingMinor(), currency) + " is outstanding on this loan");
        }
        repaidMinor += amountMinor;
        if (outstandingMinor() == 0) {
            status = Status.REPAID;
        }
    }

    /**
     * Checks a payment's method, reference and date; returns the normalised reference. M-Pesa and
     * bank transactions need their reference; dates cannot be in the future.
     */
    public static String validPayment(String method, String reference, LocalDate date, LocalDate today) {
        if (method == null || !METHODS.contains(method)) {
            throw new InvalidLoanException("method must be CASH, BANK or MPESA");
        }
        if (date != null && date.isAfter(today)) {
            throw new InvalidLoanException("A transaction cannot be dated in the future");
        }
        String ref = reference == null || reference.isBlank() ? null : reference.trim().toUpperCase(Locale.ROOT);
        if (ref == null && !method.equals("CASH")) {
            throw new InvalidLoanException(method + " transactions need their reference (transaction code or slip number)");
        }
        if (ref != null && ref.length() > 100) {
            throw new InvalidLoanException("reference must be at most 100 characters");
        }
        return ref;
    }

    // ---- schedule -------------------------------------------------------------------------------

    /**
     * The repayment schedule, with repayments applied to the oldest instalment first. Empty until
     * the loan is disbursed.
     */
    public List<Instalment> schedule(LocalDate today) {
        if (disbursedOn == null) {
            return List.of();
        }
        long total = totalDueMinor();
        long regular = total / termMonths;
        long unallocated = repaidMinor;
        List<Instalment> schedule = new ArrayList<>(termMonths);
        for (int n = 1; n <= termMonths; n++) {
            long amount = n == termMonths ? total - regular * (termMonths - 1) : regular;
            long paid = Math.min(unallocated, amount);
            unallocated -= paid;
            schedule.add(new Instalment(n, disbursedOn.plusMonths(n), amount, paid, today));
        }
        return schedule;
    }

    /** What is past due and unpaid today. */
    public long arrearsMinor(LocalDate today) {
        return schedule(today).stream().filter(Instalment::overdue).mapToLong(Instalment::unpaidMinor).sum();
    }

    /** Days since the oldest instalment still unpaid fell due; 0 when nothing is overdue. */
    public int daysInArrears(LocalDate today) {
        return schedule(today).stream().filter(Instalment::overdue).findFirst()
                .map(i -> (int) java.time.temporal.ChronoUnit.DAYS.between(i.dueDate(), today))
                .orElse(0);
    }

    /** The first instalment not fully paid. */
    public Optional<Instalment> nextInstalment(LocalDate today) {
        return schedule(today).stream().filter(i -> i.unpaidMinor() > 0).findFirst();
    }

    public LocalDate maturityDate() {
        return disbursedOn == null ? null : disbursedOn.plusMonths(termMonths);
    }

    public long totalDueMinor() {
        return principalMinor + interestMinor;
    }

    /** What the borrower still owes. Nothing is owed until the money is disbursed. */
    public long outstandingMinor() {
        return status == Status.ACTIVE || status == Status.REPAID ? totalDueMinor() - repaidMinor : 0;
    }

    public long acceptedGuarantors() {
        return guarantors.stream().filter(g -> g.status() == Guarantor.Status.ACCEPTED).count();
    }

    public Optional<Guarantor> guarantor(UUID userId) {
        return guarantors.stream().filter(g -> g.userId().equals(userId)).findFirst();
    }

    // ---- helpers --------------------------------------------------------------------------------

    private void withdrawPendingRequests(Instant now) {
        for (Guarantor g : List.copyOf(guarantors)) {
            if (g.status() == Guarantor.Status.REQUESTED) {
                replace(g.respond(Guarantor.Status.WITHDRAWN, now));
            }
        }
    }

    private void replace(Guarantor updated) {
        guarantors.replaceAll(g -> g.userId().equals(updated.userId()) ? updated : g);
    }

    private void requireBorrower(UUID actorUserId) {
        if (!borrowerUserId.equals(actorUserId)) {
            throw new NotTheBorrowerException();
        }
    }

    private void requireNotBorrower(UUID officialUserId) {
        if (borrowerUserId.equals(officialUserId)) {
            throw new SelfHandlingNotAllowedException();
        }
    }

    private void requireStatus(String action, Status required) {
        if (status != required) {
            throw new WrongLoanStatusException(action, status);
        }
    }

    private void requireUndisbursed(String action) {
        if (!UNDISBURSED.contains(status)) {
            throw new WrongLoanStatusException(action, status);
        }
    }

    /** E.g. "KES 5,000.00". */
    public static String formatted(long minor, String currency) {
        int digits = Math.max(Currency.getInstance(currency).getDefaultFractionDigits(), 0);
        return currency + " " + new DecimalFormat(digits == 0 ? "#,##0" : "#,##0." + "0".repeat(digits))
                .format(BigDecimal.valueOf(minor, digits));
    }

    public UUID id() { return id; }
    public UUID chamaId() { return chamaId; }
    public UUID borrowerUserId() { return borrowerUserId; }
    public UUID productId() { return productId; }
    public long principalMinor() { return principalMinor; }
    public int interestBasisPoints() { return interestBasisPoints; }
    public long interestMinor() { return interestMinor; }
    public int termMonths() { return termMonths; }
    public String purpose() { return purpose; }
    public String currency() { return currency; }
    public LocalDate appliedOn() { return appliedOn; }
    public int requiredGuarantors() { return requiredGuarantors; }
    public int requiredApprovals() { return requiredApprovals; }
    public List<Guarantor> guarantors() { return List.copyOf(guarantors); }
    public List<Approval> approvals() { return List.copyOf(approvals); }
    public Status status() { return status; }
    public UUID rejectedBy() { return rejectedBy; }
    public Instant rejectedAt() { return rejectedAt; }
    public String rejectionReason() { return rejectionReason; }
    public Instant cancelledAt() { return cancelledAt; }
    public LocalDate disbursedOn() { return disbursedOn; }
    public UUID disbursedBy() { return disbursedBy; }
    public String disbursementMethod() { return disbursementMethod; }
    public String disbursementReference() { return disbursementReference; }
    public UUID disbursementEntryId() { return disbursementEntryId; }
    public long repaidMinor() { return repaidMinor; }
    public Instant createdAt() { return createdAt; }
    public long version() { return version; }

    /** A member asked to guarantee the loan. */
    public record Guarantor(UUID userId, Status status, Instant requestedAt, Instant respondedAt) {

        public enum Status {
            REQUESTED,
            ACCEPTED,
            DECLINED,
            /** No longer needed: enough others accepted, or the loan was rejected or cancelled. */
            WITHDRAWN
        }

        static Guarantor requested(UUID userId, Instant now) {
            return new Guarantor(userId, Status.REQUESTED, now, null);
        }

        Guarantor respond(Status newStatus, Instant now) {
            return new Guarantor(userId, newStatus, requestedAt, now);
        }
    }

    /** One official's approval. */
    public record Approval(UUID approverUserId, String note, Instant approvedAt) {
    }

    /**
     * One monthly instalment.
     *
     * @param overdue due before today and not fully paid
     */
    public record Instalment(int number, LocalDate dueDate, long amountMinor, long paidMinor, boolean overdue) {

        Instalment(int number, LocalDate dueDate, long amountMinor, long paidMinor, LocalDate today) {
            this(number, dueDate, amountMinor, paidMinor, paidMinor < amountMinor && dueDate.isBefore(today));
        }

        public long unpaidMinor() {
            return amountMinor - paidMinor;
        }

        public String status() {
            if (paidMinor >= amountMinor) {
                return "PAID";
            }
            return overdue ? "OVERDUE" : paidMinor > 0 ? "PARTLY_PAID" : "DUE";
        }
    }
}
