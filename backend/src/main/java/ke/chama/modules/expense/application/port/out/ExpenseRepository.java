package ke.chama.modules.expense.application.port.out;

import ke.chama.modules.expense.domain.model.Expense;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Row-level security limits every query to the current chama. Expenses load with their approvals. */
public interface ExpenseRepository {

    void insert(Expense expense);

    /** Saves the expense and its approvals; false if it changed since it was read. */
    boolean update(Expense expense);

    Optional<Expense> expense(UUID chamaId, UUID expenseId);

    Page<Expense> expenses(UUID chamaId, Expense.Status status, UUID investmentId, LocalDate from, LocalDate to,
                           Pageable pageable);

    /** Paid expenses per investment they were charged to. */
    Map<UUID, Long> paidByInvestment(UUID chamaId);

    boolean referenceExists(UUID chamaId, String method, String reference);

    /** Paid expenses between two dates (inclusive, by payment date), per account code, largest first. */
    List<AccountTotal> paidByAccount(UUID chamaId, LocalDate from, LocalDate to);

    /** Pending and approved expenses not yet paid. */
    long awaitingPaymentMinor(UUID chamaId);

    record AccountTotal(String accountCode, long totalMinor, int count) {
    }
}
