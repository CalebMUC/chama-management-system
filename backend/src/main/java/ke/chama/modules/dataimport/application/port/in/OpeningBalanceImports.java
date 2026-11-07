package ke.chama.modules.dataimport.application.port.in;

import ke.chama.modules.dataimport.domain.model.Problem;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Bringing a chama's records over from spreadsheets: download the template, fill it in, preview it
 * (every row checked, nothing saved), then import it once. Everything is imported in one
 * transaction: either all of it or none.
 */
public interface OpeningBalanceImports {

    /**
     * The .xlsx template to fill in, with a savings column on the Members sheet for each of the
     * chama's savings products besides the default.
     */
    byte[] template(UUID chamaId);

    /** Checks the file without saving anything. */
    PreviewView preview(UUID chamaId, byte[] file, LocalDate asOf);

    /** Imports the file; refused if it has any error or opening balances were already imported. */
    ImportView importBalances(UUID chamaId, UUID actorUserId, String fileName, byte[] file, LocalDate asOf);

    /** The chama's import, if it has been done. */
    Optional<ImportView> imported(UUID chamaId);

    /**
     * Amounts are minor units of {@code currency}.
     *
     * @param retainedSurplusMinor what the chama owns less what it owes members: the surplus brought
     *                             forward (negative when savings exceed what the chama holds)
     */
    record SummaryView(String currency,
                       int newMembers,
                       int existingMembers,
                       long savingsMinor,
                       int loans,
                       long loansOutstandingMinor,
                       long penaltiesMinor,
                       long cashMinor,
                       long bankMinor,
                       long mobileMoneyMinor,
                       int investments,
                       long investmentsMinor,
                       long retainedSurplusMinor) {
    }

    record ProblemView(Problem.Severity severity, String sheet, Integer row, String column, String message) {
    }

    /** @param importable true when there are no errors (warnings do not stop an import) */
    record PreviewView(boolean importable,
                       LocalDate asOf,
                       long errors,
                       long warnings,
                       SummaryView summary,
                       List<ProblemView> problems) {
    }

    /** @param ledgerEntryId the opening-balance journal entry; null if no money was brought forward */
    record ImportView(UUID id,
                      LocalDate asOf,
                      String fileName,
                      UUID importedBy,
                      Instant importedAt,
                      UUID ledgerEntryId,
                      SummaryView summary,
                      List<ProblemView> warnings) {
    }
}
