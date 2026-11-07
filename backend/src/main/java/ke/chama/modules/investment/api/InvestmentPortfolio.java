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
     */
    record InvestmentSnapshot(UUID id, String name, String type, String status, long investedMinor,
                              long bookValueMinor, long marketValueMinor, long incomeMinor, LocalDate fundedOn,
                              LocalDate closedOn) {
    }
}
