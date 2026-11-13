package ke.chama.modules.investment.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Bringing over the investments of a chama that ran for years before it used the system: each
 * funded on its own day, then everything that happened to it since (top-ups, income, withdrawals,
 * valuations, closure), posted to the ledger on their own days. Writes run in the caller's
 * transaction; nobody is emailed.
 */
public interface InvestmentHistory {

    /** Investment type names, e.g. MONEY_MARKET_FUND, LAND. */
    Set<String> typeNames();

    /** References used by investment transactions, as METHOD:REFERENCE upper case. */
    Set<String> usedReferences(UUID chamaId);

    /**
     * @param kind   DEPOSIT, INCOME, WITHDRAWAL, CLOSURE or VALUATION
     * @param method CASH, BANK or MPESA; REINVESTED for income that stayed in; ignored for a valuation
     */
    record HistoricalMovement(String kind, LocalDate date, long amountMinor, String method, String reference,
                              String note) {
    }

    /**
     * @param amountMinor what it was funded with
     * @param method      how it was funded: CASH, BANK or MPESA
     * @param movements   in date order
     */
    record HistoricalInvestment(String name, String type, String institution, String description, long amountMinor,
                                LocalDate fundedOn, String method, String reference, Integer expectedReturnBasisPoints,
                                LocalDate maturityDate, List<HistoricalMovement> movements) {

        public HistoricalInvestment {
            movements = List.copyOf(movements);
        }
    }

    /** Records the investments and their histories, tagged with the batch. Returns how many. */
    int importInvestments(UUID chamaId, UUID actorUserId, UUID batchId, List<HistoricalInvestment> investments);

    /** Why the batch's investments cannot be removed now, e.g. income was recorded on one since. */
    Optional<String> undoBlocked(UUID chamaId, UUID batchId);

    /** Removes the batch's investments, reversing their entries on their own days. */
    int undoInvestments(UUID chamaId, UUID actorUserId, UUID batchId);
}
