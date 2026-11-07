package ke.chama.modules.transaction.api;

import ke.chama.shared.domain.Money;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The chama's books. Every money movement in every module goes through {@link #post}; balances
 * are only ever derived from posted entries. Runs in the caller's transaction, so a business
 * record and its ledger entry commit together or not at all.
 */
public interface Ledger {

    /**
     * Posts a balanced entry and returns its id. Idempotent per (sourceType, sourceId): posting
     * the same source again returns the existing entry's id without posting twice.
     */
    UUID post(PostingRequest request);

    /** Posts the mirror image of an entry. An entry can be reversed once; a reversal cannot be reversed. */
    UUID reverse(UUID chamaId, UUID entryId, UUID actorUserId, String reason);

    /** The account's balance on its normal side (e.g. a positive asset, a positive liability). */
    Money balance(UUID chamaId, LedgerAccountKey account);

    /** One member's balance on a member-ledger account, e.g. their savings. */
    Money memberBalance(UUID chamaId, UUID memberUserId, LedgerAccountKey account);

    /** One member's balance on the member-ledger account with this code, e.g. a savings product's account. */
    Money memberBalance(UUID chamaId, UUID memberUserId, String accountCode);

    /** Every member's balance on the member-ledger account with this code; members without lines are absent. */
    Map<UUID, Long> memberBalances(UUID chamaId, String accountCode);

    /**
     * One member's lines on a member-ledger account, oldest first, with a running balance.
     * {@code from} and {@code to} are optional and inclusive.
     */
    Statement memberStatement(UUID chamaId, String accountCode, UUID memberUserId, LocalDate from, LocalDate to);

    /**
     * Every line on an account (for every member, on a member-ledger account), oldest first, with a
     * running balance, e.g. the cash book. {@code from} and {@code to} are optional and inclusive.
     */
    Statement accountStatement(UUID chamaId, String accountCode, LocalDate from, LocalDate to);

    /**
     * Every account in the chart with its movements for entries dated {@code from}..{@code to}
     * (inclusive; either may be null for an open end), by code. Accounts without movements are
     * included with zeros.
     */
    List<AccountTotal> accountTotals(UUID chamaId, LocalDate from, LocalDate to);

    /** The latest entries, newest first. */
    List<EntrySummary> recentEntries(UUID chamaId, int limit);

    /** The account with this code in the chama's chart, if any. */
    Optional<LedgerAccountSummary> account(UUID chamaId, String code);

    /** The system account with this key, e.g. the code of "Member savings". */
    Optional<LedgerAccountSummary> account(UUID chamaId, LedgerAccountKey key);

    /**
     * Adds an account that keeps a balance per member (every line names a member), e.g. "2010
     * Share capital" for a new savings product. Member-ledger accounts cannot be deactivated.
     *
     * @param type LIABILITY or EQUITY
     */
    LedgerAccountSummary openMemberLedgerAccount(UUID chamaId, String code, String name, String type, UUID actorUserId);

    /**
     * @param memberUserId        the member, for one member's statement; null for the whole account
     * @param openingBalanceMinor the balance before {@code from} (zero without {@code from})
     */
    record Statement(String accountCode, String accountName, String currency, UUID memberUserId,
                     LocalDate from, LocalDate to, long openingBalanceMinor, long closingBalanceMinor,
                     List<Line> lines) {

        /** @param memberUserId the member a line of a member-ledger account belongs to */
        public record Line(UUID entryId, long entryNumber, LocalDate entryDate, String description, String reference,
                           String sourceType, long debitMinor, long creditMinor, long balanceMinor, UUID memberUserId,
                           String memo) {
        }
    }

    /**
     * One account's movements over a date range.
     *
     * @param type         ASSET, LIABILITY, EQUITY, INCOME or EXPENSE
     * @param balanceMinor debits less credits for assets and expenses, credits less debits otherwise
     */
    record AccountTotal(String code, String name, String type, LedgerAccountKey systemKey, boolean memberLedger,
                        long debitMinor, long creditMinor, long balanceMinor) {
    }

    /** @param totalMinor the entry's debits (which equal its credits) */
    record EntrySummary(UUID id, long number, LocalDate entryDate, String description, String reference,
                        String sourceType, long totalMinor, boolean reversal) {
    }
}
