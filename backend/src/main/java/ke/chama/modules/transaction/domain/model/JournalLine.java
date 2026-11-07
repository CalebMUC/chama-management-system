package ke.chama.modules.transaction.domain.model;

import ke.chama.modules.transaction.api.EntrySide;

import java.util.Objects;
import java.util.UUID;

/**
 * One side of a journal entry: an amount debited or credited to an account.
 *
 * @param amountMinor   always positive; {@code side} says which way it goes
 * @param memberUserId  the member, on member-ledger accounts only
 */
public record JournalLine(UUID accountId,
                          EntrySide side,
                          long amountMinor,
                          UUID memberUserId,
                          String memo) {

    public JournalLine {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(side, "side");
    }

    public long debitMinor() {
        return side == EntrySide.DEBIT ? amountMinor : 0;
    }

    public long creditMinor() {
        return side == EntrySide.CREDIT ? amountMinor : 0;
    }

    JournalLine mirrored() {
        return new JournalLine(accountId, side == EntrySide.DEBIT ? EntrySide.CREDIT : EntrySide.DEBIT,
                amountMinor, memberUserId, memo);
    }
}
