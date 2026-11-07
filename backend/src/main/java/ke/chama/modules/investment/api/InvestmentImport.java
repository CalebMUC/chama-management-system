package ke.chama.modules.investment.api;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * Investments the chama already holds when it moves over from its earlier records (spreadsheets),
 * e.g. a money market fund balance or an Airbnb unit. Runs in the caller's transaction. The caller
 * posts the opening-balance ledger entry; the investment only records it.
 */
public interface InvestmentImport {

    /** Investment type names, e.g. MONEY_MARKET_FUND, BUSINESS. */
    Set<String> typeNames();

    /** Records an active investment carried at {@code amountMinor} (its book value) from {@code investedOn}. */
    UUID importInvestment(UUID chamaId, ImportedInvestment investment, UUID importedBy, UUID openingEntryId);

    /**
     * @param amountMinor               what the books carry it at: what was put in, less anything taken out
     * @param currentValueMinor         what it is worth on {@code asOf}; optional, becomes its first valuation
     * @param expectedReturnBasisPoints expected yearly return (1250 = 12.5%); optional
     */
    record ImportedInvestment(String name, String type, String institution, LocalDate investedOn, long amountMinor,
                              Long currentValueMinor, Integer expectedReturnBasisPoints, LocalDate asOf,
                              String currency) {
    }
}
