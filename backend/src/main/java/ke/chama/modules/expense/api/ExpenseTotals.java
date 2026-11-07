package ke.chama.modules.expense.api;

import java.util.Map;
import java.util.UUID;

/** Spending other modules may read, e.g. what an investment cost to run. */
public interface ExpenseTotals {

    /** Paid expenses charged to each investment, by investment id. Investments with none are absent. */
    Map<UUID, Long> paidByInvestment(UUID chamaId);
}
