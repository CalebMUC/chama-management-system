package ke.chama.modules.expense.application.port.in;

import ke.chama.modules.expense.domain.model.Expense;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Expenses of the caller's chama, with maker-checker approval. Paying posts to the ledger; every
 * step is audited. Every member can see every expense.
 */
public interface ExpenseUseCases {

    ExpenseView request(UUID chamaId, UUID actorUserId, NewExpense expense);

    ExpenseView approve(UUID chamaId, UUID actorUserId, UUID expenseId, String note);

    ExpenseView reject(UUID chamaId, UUID actorUserId, UUID expenseId, String reason);

    ExpenseView cancel(UUID chamaId, UUID actorUserId, UUID expenseId);

    ExpenseView pay(UUID chamaId, UUID actorUserId, UUID expenseId, Payment payment);

    ExpenseView expense(UUID chamaId, UUID expenseId);

    /** Every filter is optional; dates filter on when the expense was incurred. Newest first. */
    Page<ExpenseView> expenses(UUID chamaId, Expense.Status status, LocalDate from, LocalDate to, Pageable pageable);

    /** What was paid out between two dates (inclusive, by payment date), per account, and what awaits payment. */
    SummaryView summary(UUID chamaId, LocalDate from, LocalDate to);

    /** @param accountCode the expense account to charge; null means 5000 Operating expenses */
    record NewExpense(long amountMinor, String payee, String description, String accountCode, LocalDate incurredOn) {
    }

    record Payment(String method, String reference, LocalDate paidOn) {
    }

    record ApprovalView(UUID approverUserId, String name, String note, Instant approvedAt) {
    }

    /** Amounts are minor units of {@code currency}. */
    record ExpenseView(UUID id,
                       long amountMinor,
                       String currency,
                       String payee,
                       String description,
                       String accountCode,
                       String accountName,
                       LocalDate incurredOn,
                       Expense.Status status,
                       UUID requestedBy,
                       String requestedByName,
                       int requiredApprovals,
                       List<ApprovalView> approvals,
                       UUID rejectedBy,
                       Instant rejectedAt,
                       String rejectionReason,
                       Instant cancelledAt,
                       LocalDate paidOn,
                       UUID paidBy,
                       String paymentMethod,
                       String paymentReference,
                       UUID ledgerEntryId) {
    }

    record AccountTotal(String accountCode, String accountName, long totalMinor, int count) {
    }

    /** @param awaitingPaymentMinor expenses still pending approval or approved but not yet paid */
    record SummaryView(String currency,
                       LocalDate from,
                       LocalDate to,
                       long paidMinor,
                       int paidCount,
                       long awaitingPaymentMinor,
                       List<AccountTotal> byAccount) {
    }
}
