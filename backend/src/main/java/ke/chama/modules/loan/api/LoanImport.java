package ke.chama.modules.loan.api;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * Loans brought over from a chama's earlier records (spreadsheets). Runs in the caller's
 * transaction. The caller posts the opening-balance ledger entry; the loan only records it.
 */
public interface LoanImport {

    int MAX_TERM_MONTHS = 36;

    /** Members who already have a loan in progress in the system: they cannot have one imported too. */
    Set<UUID> borrowersWithOpenLoans(UUID chamaId);

    /**
     * Records an active loan, already disbursed, with what was repaid so far. What is still owed
     * (principal + interest - repaid) must match the borrower's line in the opening entry.
     */
    UUID importLoan(UUID chamaId, ImportedLoan loan, UUID importedBy, UUID openingEntryId);

    /**
     * @param interestMinor the flat interest agreed for the whole term
     * @param repaidMinor   repaid before the cut-over date; less than principal + interest
     */
    record ImportedLoan(UUID borrowerUserId, long principalMinor, long interestMinor, int termMonths,
                        LocalDate disbursedOn, long repaidMinor, String purpose, String currency) {
    }
}
