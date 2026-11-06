package ke.chama.modules.expense.domain.model;

import ke.chama.modules.expense.domain.exception.ExpenseExceptions.AlreadyApprovedException;
import ke.chama.modules.expense.domain.exception.ExpenseExceptions.InvalidExpenseException;
import ke.chama.modules.expense.domain.exception.ExpenseExceptions.NotTheRequesterException;
import ke.chama.modules.expense.domain.exception.ExpenseExceptions.SelfApprovalNotAllowedException;
import ke.chama.modules.expense.domain.exception.ExpenseExceptions.WrongExpenseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Money the chama spends, with maker-checker approval.
 * <pre>
 * PENDING_APPROVAL ──(quorum of officials other than the requester)──► APPROVED ──(paid out)──► PAID
 *        └──────────── rejected by an approver / cancelled by the requester ──────┘
 * </pre>
 * Paying posts it to the ledger: the expense account is debited, the account the money left credited.
 */
public final class Expense {

    static final int MAX_TEXT_LENGTH = 300;
    static final int MAX_PAYEE_LENGTH = 120;
    private static final Set<String> METHODS = Set.of("CASH", "BANK", "MPESA");

    public enum Status {
        PENDING_APPROVAL,
        APPROVED,
        PAID,
        REJECTED,
        CANCELLED
    }

    private static final Set<Status> UNPAID = EnumSet.of(Status.PENDING_APPROVAL, Status.APPROVED);

    private final UUID id;
    private final UUID chamaId;
    private final long amountMinor;
    private final String currency;
    private final String payee;
    private final String description;
    private final String accountCode;
    private final LocalDate incurredOn;
    private final UUID requestedBy;
    private final int requiredApprovals;
    private final List<Approval> approvals;
    private Status status;
    private UUID rejectedBy;
    private Instant rejectedAt;
    private String rejectionReason;
    private Instant cancelledAt;
    private LocalDate paidOn;
    private UUID paidBy;
    private String paymentMethod;
    private String paymentReference;
    private UUID paymentEntryId;
    private final Instant createdAt;
    private final long version;

    public Expense(UUID id, UUID chamaId, long amountMinor, String currency, String payee, String description,
                   String accountCode, LocalDate incurredOn, UUID requestedBy, int requiredApprovals,
                   List<Approval> approvals, Status status, UUID rejectedBy, Instant rejectedAt, String rejectionReason,
                   Instant cancelledAt, LocalDate paidOn, UUID paidBy, String paymentMethod, String paymentReference,
                   UUID paymentEntryId, Instant createdAt, long version) {
        this.id = Objects.requireNonNull(id);
        this.chamaId = Objects.requireNonNull(chamaId);
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.payee = payee;
        this.description = description;
        this.accountCode = accountCode;
        this.incurredOn = incurredOn;
        this.requestedBy = Objects.requireNonNull(requestedBy);
        this.requiredApprovals = requiredApprovals;
        this.approvals = new ArrayList<>(approvals);
        this.status = status;
        this.rejectedBy = rejectedBy;
        this.rejectedAt = rejectedAt;
        this.rejectionReason = rejectionReason;
        this.cancelledAt = cancelledAt;
        this.paidOn = paidOn;
        this.paidBy = paidBy;
        this.paymentMethod = paymentMethod;
        this.paymentReference = paymentReference;
        this.paymentEntryId = paymentEntryId;
        this.createdAt = createdAt;
        this.version = version;
    }

    /**
     * A new expense awaiting approval. The caller has checked that {@code accountCode} is an
     * active expense account.
     *
     * @param requiredApprovals the chama's approval quorum when the expense was recorded
     */
    public static Expense request(UUID chamaId, UUID requestedBy, long amountMinor, String currency, String payee,
                                  String description, String accountCode, LocalDate incurredOn, int requiredApprovals,
                                  LocalDate today, Instant now) {
        if (amountMinor <= 0) {
            throw new InvalidExpenseException("The amount must be greater than zero");
        }
        String to = text(payee, MAX_PAYEE_LENGTH, "payee");
        String what = text(description, MAX_TEXT_LENGTH, "description");
        LocalDate date = incurredOn == null ? today : incurredOn;
        if (date.isAfter(today)) {
            throw new InvalidExpenseException("An expense cannot be dated in the future");
        }
        return new Expense(UUID.randomUUID(), chamaId, amountMinor, currency, to, what, accountCode, date, requestedBy,
                Math.max(requiredApprovals, 1), List.of(), Status.PENDING_APPROVAL, null, null, null, null, null, null,
                null, null, null, now, 0);
    }

    /** One official's approval; the expense is approved once {@code requiredApprovals} distinct officials approve. */
    public void approve(UUID approverUserId, String note, Instant now) {
        requireNotRequester(approverUserId);
        requireStatus("approve", Status.PENDING_APPROVAL);
        if (approvals.stream().anyMatch(a -> a.approverUserId().equals(approverUserId))) {
            throw new AlreadyApprovedException();
        }
        String text = note == null || note.isBlank() ? null : note.trim();
        if (text != null && text.length() > MAX_TEXT_LENGTH) {
            throw new InvalidExpenseException("The note must be at most " + MAX_TEXT_LENGTH + " characters");
        }
        approvals.add(new Approval(approverUserId, text, now));
        if (approvals.size() >= requiredApprovals) {
            status = Status.APPROVED;
        }
    }

    /** Any one approver can turn it down before it is paid. A reason is required. */
    public void reject(UUID officialUserId, String reason, Instant now) {
        requireNotRequester(officialUserId);
        requireUnpaid("reject");
        rejectionReason = text(reason, MAX_TEXT_LENGTH, "reason");
        status = Status.REJECTED;
        rejectedBy = officialUserId;
        rejectedAt = now;
    }

    /** The official who recorded it withdraws it before it is paid. */
    public void cancel(UUID actorUserId, Instant now) {
        if (!requestedBy.equals(actorUserId)) {
            throw new NotTheRequesterException();
        }
        requireUnpaid("cancel");
        status = Status.CANCELLED;
        cancelledAt = now;
    }

    /** Pays an approved expense. The caller posts it to the ledger and records the entry. */
    public void pay(UUID officialUserId, String method, String reference, LocalDate date, LocalDate today) {
        requireStatus("pay", Status.APPROVED);
        if (method == null || !METHODS.contains(method)) {
            throw new InvalidExpenseException("method must be CASH, BANK or MPESA");
        }
        LocalDate on = date == null ? today : date;
        if (on.isAfter(today)) {
            throw new InvalidExpenseException("A payment cannot be dated in the future");
        }
        String ref = reference == null || reference.isBlank() ? null : reference.trim().toUpperCase(Locale.ROOT);
        if (ref == null && !method.equals("CASH")) {
            throw new InvalidExpenseException(method + " payments need their reference (transaction code or slip number)");
        }
        if (ref != null && ref.length() > 100) {
            throw new InvalidExpenseException("reference must be at most 100 characters");
        }
        status = Status.PAID;
        paidOn = on;
        paidBy = officialUserId;
        paymentMethod = method;
        paymentReference = ref;
    }

    public void paymentPostedAs(UUID entryId) {
        this.paymentEntryId = Objects.requireNonNull(entryId);
    }

    private void requireNotRequester(UUID officialUserId) {
        if (requestedBy.equals(officialUserId)) {
            throw new SelfApprovalNotAllowedException();
        }
    }

    private void requireStatus(String action, Status required) {
        if (status != required) {
            throw new WrongExpenseStatusException(action, status);
        }
    }

    private void requireUnpaid(String action) {
        if (!UNPAID.contains(status)) {
            throw new WrongExpenseStatusException(action, status);
        }
    }

    private static String text(String value, int max, String field) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) {
            throw new InvalidExpenseException("A " + field + " is required");
        }
        if (v.length() > max) {
            throw new InvalidExpenseException("The " + field + " must be at most " + max + " characters");
        }
        return v;
    }

    public UUID id() { return id; }
    public UUID chamaId() { return chamaId; }
    public long amountMinor() { return amountMinor; }
    public String currency() { return currency; }
    public String payee() { return payee; }
    public String description() { return description; }
    public String accountCode() { return accountCode; }
    public LocalDate incurredOn() { return incurredOn; }
    public UUID requestedBy() { return requestedBy; }
    public int requiredApprovals() { return requiredApprovals; }
    public List<Approval> approvals() { return List.copyOf(approvals); }
    public Status status() { return status; }
    public UUID rejectedBy() { return rejectedBy; }
    public Instant rejectedAt() { return rejectedAt; }
    public String rejectionReason() { return rejectionReason; }
    public Instant cancelledAt() { return cancelledAt; }
    public LocalDate paidOn() { return paidOn; }
    public UUID paidBy() { return paidBy; }
    public String paymentMethod() { return paymentMethod; }
    public String paymentReference() { return paymentReference; }
    public UUID paymentEntryId() { return paymentEntryId; }
    public Instant createdAt() { return createdAt; }
    public long version() { return version; }

    /** One official's approval. */
    public record Approval(UUID approverUserId, String note, Instant approvedAt) {
    }
}
