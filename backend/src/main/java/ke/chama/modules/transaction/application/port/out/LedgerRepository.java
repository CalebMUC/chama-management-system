package ke.chama.modules.transaction.application.port.out;

import ke.chama.modules.transaction.api.LedgerAccountKey;
import ke.chama.modules.transaction.domain.model.JournalEntry;
import ke.chama.modules.transaction.domain.model.LedgerAccount;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for the ledger. Entries are only ever inserted. Row-level security limits every
 * query to the current chama; {@code chamaId} is still passed so queries stay explicit.
 */
public interface LedgerRepository {

    // ---- accounts ---------------------------------------------------------------------------

    void insertAccounts(Collection<LedgerAccount> accounts);

    List<LedgerAccount> accounts(UUID chamaId);

    Optional<LedgerAccount> accountById(UUID chamaId, UUID accountId);

    Optional<LedgerAccount> accountByKey(UUID chamaId, LedgerAccountKey key);

    Optional<LedgerAccount> accountByCode(UUID chamaId, String code);

    void setAccountActive(UUID chamaId, UUID accountId, boolean active);

    // ---- entries ----------------------------------------------------------------------------

    /** Starts the chama's entry numbering (at onboarding). */
    void initSequence(UUID chamaId);

    /** The next entry number; serialised per chama, so numbers are gap-free within a commit. */
    long nextEntryNumber(UUID chamaId);

    void insertEntry(JournalEntry entry);

    Optional<JournalEntry> entry(UUID chamaId, UUID entryId);

    Optional<UUID> entryIdForSource(UUID chamaId, String sourceType, String sourceId);

    /** The reversal of {@code entryId}, if it has been reversed. */
    Optional<UUID> reversalOf(UUID chamaId, UUID entryId);

    Map<UUID, UUID> reversalsOf(UUID chamaId, Collection<UUID> entryIds);

    Page<JournalEntry> entries(UUID chamaId, LocalDate from, LocalDate to, String sourceType, Pageable pageable);

    // ---- balances ---------------------------------------------------------------------------

    record Totals(long debits, long credits) {
        public static final Totals ZERO = new Totals(0, 0);
    }

    /** Debit and credit totals per account for entries dated on or before {@code asOf}. */
    Map<UUID, Totals> totalsByAccount(UUID chamaId, LocalDate asOf);

    /** Totals of one account, optionally for one member, dated before {@code before} (exclusive); null = all. */
    Totals accountTotals(UUID chamaId, UUID accountId, UUID memberUserId, LocalDate before);

    /** A line of an account statement, oldest first. */
    record StatementRow(UUID entryId, long entryNumber, LocalDate entryDate, String description, String reference,
                        String sourceType, long debitMinor, long creditMinor, UUID memberUserId, String memo) {
    }

    List<StatementRow> statement(UUID chamaId, UUID accountId, UUID memberUserId, LocalDate from, LocalDate to);
}
