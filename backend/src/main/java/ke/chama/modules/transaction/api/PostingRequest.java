package ke.chama.modules.transaction.api;

import ke.chama.shared.domain.Money;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One balanced journal entry to post. Example, a member's KES 5,000 contribution paid by M-Pesa:
 * <pre>{@code
 * new PostingRequest(chamaId, today, "Contribution for November", "QK7H2XY9ZB",
 *         "CONTRIBUTION", contribution.getId().toString(), treasurerId, List.of(
 *             PostingLine.debit(LedgerAccountKey.MOBILE_MONEY, Money.kes(500_000)),
 *             PostingLine.credit(LedgerAccountKey.MEMBER_SAVINGS, Money.kes(500_000)).forMember(memberUserId)));
 * }</pre>
 *
 * @param entryDate  the business date; null means today in the chama's time zone. Never in the future.
 * @param reference  an external reference such as an M-Pesa code or bank slip number; optional
 * @param sourceType what caused the entry, e.g. {@code CONTRIBUTION}
 * @param sourceId   that record's id. Together with {@code sourceType} it makes posting idempotent:
 *                   posting the same source again returns the existing entry. Null for manual entries.
 * @param recordedBy the accountable user; null only for system jobs
 */
public record PostingRequest(UUID chamaId,
                             LocalDate entryDate,
                             String description,
                             String reference,
                             String sourceType,
                             String sourceId,
                             UUID recordedBy,
                             List<PostingLine> lines) {

    public static final String SOURCE_MANUAL = "MANUAL";
    public static final String SOURCE_REVERSAL = "REVERSAL";

    public PostingRequest {
        Objects.requireNonNull(chamaId, "chamaId");
        Objects.requireNonNull(sourceType, "sourceType");
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /**
     * One side of the entry. Name the account by {@code key} (system accounts) or {@code code}
     * (any account). {@code memberUserId} is required on member-ledger accounts (member savings,
     * loans receivable) and not allowed elsewhere.
     */
    public record PostingLine(LedgerAccountKey key,
                              String code,
                              EntrySide side,
                              Money amount,
                              UUID memberUserId,
                              String memo) {

        public static PostingLine debit(LedgerAccountKey key, Money amount) {
            return new PostingLine(key, null, EntrySide.DEBIT, amount, null, null);
        }

        public static PostingLine credit(LedgerAccountKey key, Money amount) {
            return new PostingLine(key, null, EntrySide.CREDIT, amount, null, null);
        }

        public PostingLine forMember(UUID userId) {
            return new PostingLine(key, code, side, amount, userId, memo);
        }

        public PostingLine withMemo(String text) {
            return new PostingLine(key, code, side, amount, memberUserId, text);
        }
    }
}
