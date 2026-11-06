package ke.chama.modules.transaction.application.port.in;

import ke.chama.modules.transaction.api.LedgerAccountKey;
import ke.chama.modules.transaction.domain.model.AccountType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Read models of the ledger. Amounts are in minor units (cents) of {@code currency}, like every
 * amount in the API. Balances are on the account's normal side: a positive asset is money held,
 * a positive liability is money owed.
 */
public final class LedgerViews {

    private LedgerViews() {
    }

    public record AccountView(UUID id,
                              String code,
                              String name,
                              AccountType type,
                              LedgerAccountKey systemKey,
                              String currency,
                              boolean memberLedger,
                              boolean active,
                              long balanceMinor) {
    }

    public record LineView(UUID accountId,
                           String accountCode,
                           String accountName,
                           long debitMinor,
                           long creditMinor,
                           UUID memberUserId,
                           String memo) {
    }

    /**
     * @param reversesEntryId   set on a reversal: the entry it cancels
     * @param reversedByEntryId set on an entry that has been reversed: the reversal
     */
    public record EntryView(UUID id,
                            long number,
                            LocalDate entryDate,
                            String description,
                            String reference,
                            String sourceType,
                            String sourceId,
                            UUID reversesEntryId,
                            UUID reversedByEntryId,
                            UUID recordedBy,
                            Instant recordedAt,
                            long totalMinor,
                            List<LineView> lines) {
    }

    /** Every movement of one account (optionally one member's) between two dates, with running balances. */
    public record StatementView(AccountView account,
                                UUID memberUserId,
                                LocalDate from,
                                LocalDate to,
                                long openingBalanceMinor,
                                long closingBalanceMinor,
                                List<StatementLine> lines) {
    }

    public record StatementLine(UUID entryId,
                                long entryNumber,
                                LocalDate entryDate,
                                String description,
                                String reference,
                                String sourceType,
                                long debitMinor,
                                long creditMinor,
                                long balanceMinor,
                                UUID memberUserId,
                                String memo) {
    }

    /** Total debits equal total credits whenever the books are consistent ({@code balanced}). */
    public record TrialBalanceView(LocalDate asOf,
                                   String currency,
                                   List<TrialBalanceRow> rows,
                                   long totalDebitsMinor,
                                   long totalCreditsMinor,
                                   boolean balanced) {
    }

    /** Debit/credit columns hold the account's net balance on its side, as in a classic trial balance. */
    public record TrialBalanceRow(UUID accountId, String code, String name, AccountType type,
                                  long debitMinor, long creditMinor) {
    }

    /** The caller's own position in their chama. */
    public record MemberBalancesView(UUID memberUserId, String currency, long savingsMinor, long loansOutstandingMinor) {
    }
}
