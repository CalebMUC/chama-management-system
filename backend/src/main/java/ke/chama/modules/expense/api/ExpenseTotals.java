package ke.chama.modules.expense.api;

import java.util.Map;
import java.util.UUID;

/** Spending other modules may read, e.g. what an investment cost to run. */
public interface ExpenseTotals {

    /** Paid expenses charged to each investment, by investment id. Investments with none are absent. */
    Map<UUID, Long> paidByInvestment(UUID chamaId);

    /** Expenses waiting for an approval or a payment, e.g. for a dashboard. */
    ExpenseQueue queue(UUID chamaId);

    /**
     * @param pendingApproval      expenses waiting for officials to approve them
     * @param approvedUnpaid       approved but not paid yet
     * @param awaitingPaymentMinor the amount of both together
     */
    record ExpenseQueue(long pendingApproval, long approvedUnpaid, long awaitingPaymentMinor) {
    }
}
