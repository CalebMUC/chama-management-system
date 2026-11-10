package ke.chama.modules.investment.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The chama's investments, for reports and dashboards. */
public interface InvestmentPortfolio {

    /** Investments that were funded (active or closed), newest first. */
    List<InvestmentSnapshot> fundedInvestments(UUID chamaId);

    /** Proposed investments waiting for approval. */
    long awaitingApproval(UUID chamaId);

    /**
     * @param type             e.g. MONEY_MARKET_FUND, LAND, BUSINESS
     * @param status           ACTIVE or CLOSED
     * @param investedMinor    everything put in: the funding plus later deposits
     * @param bookValueMinor   what the books carry it at (in the ledger's Investments account)
     * @param marketValueMinor the latest valuation, or the book value without one
     * @param incomeMinor      returns received (interest, dividends, rent)
     * @param expensesMinor    paid expenses charged to the investment
     * @param totalReturnMinor the profit: income less expenses, plus the gain in value (realised once
     *                         closed, otherwise latest valuation less book value); negative for a loss
     * @param returnBasisPoints total return per 10,000 invested (1250 = 12.5%)
     * @param annualisedReturnBasisPoints the return per year held; null when held under 30 days
     * @param daysHeld         from funding to closure or today
     */
    record InvestmentSnapshot(UUID id, String name, String type, String status, long investedMinor,
                              long bookValueMinor, long marketValueMinor, long incomeMinor, LocalDate fundedOn,
                              LocalDate closedOn, String institution, LocalDate maturityDate, long expensesMinor,
                              long totalReturnMinor, int returnBasisPoints, Integer annualisedReturnBasisPoints,
                              long daysHeld) {

        public boolean active() {
            return "ACTIVE".equals(status);
        }

        public boolean profitable() {
            return totalReturnMinor > 0;
        }
    }
}
