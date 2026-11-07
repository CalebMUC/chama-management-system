package ke.chama.modules.transaction.api;

import ke.chama.shared.domain.Money;

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

    /** The account with this code in the chama's chart, if any. */
    Optional<LedgerAccountSummary> account(UUID chamaId, String code);
}
