package ke.chama.modules.expense.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Bringing over the expenses a chama paid in the years before it used the system: each recorded as
 * paid, posted to the ledger on the day it was paid. Writes run in the caller's transaction.
 */
public interface ExpenseHistory {

    /**
     * @param accountCode  the expense account in the chart of accounts, e.g. 5000
     * @param investmentId the investment it was charged to; may be null
     * @param method       CASH, BANK or MPESA
     */
    record HistoricalExpense(LocalDate paidOn, long amountMinor, String payee, String description, String accountCode,
                             UUID investmentId, String method, String reference) {
    }

    /** References used by expense payments, as METHOD:REFERENCE upper case. */
    Set<String> usedReferences(UUID chamaId);

    /** Records the expenses as paid, tagged with the batch. Returns how many. */
    int importExpenses(UUID chamaId, UUID actorUserId, UUID batchId, List<HistoricalExpense> expenses);

    /** Removes the batch's expenses, reversing their entries on their own days. */
    int undoExpenses(UUID chamaId, UUID actorUserId, UUID batchId);
}
