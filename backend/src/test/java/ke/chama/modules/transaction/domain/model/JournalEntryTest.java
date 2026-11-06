package ke.chama.modules.transaction.domain.model;

import ke.chama.modules.transaction.api.EntrySide;
import ke.chama.modules.transaction.api.PostingRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JournalEntryTest {

    private static final UUID CHAMA = UUID.randomUUID();
    private static final UUID BANK = UUID.randomUUID();
    private static final UUID SAVINGS = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 6);
    private static final Instant NOW = Instant.parse("2026-11-06T09:00:00Z");

    @Test
    void aBalancedEntryIsAccepted() {
        JournalEntry entry = entry(List.of(debit(BANK, 500_000), credit(SAVINGS, 500_000)));

        assertThat(entry.totalMinor()).isEqualTo(500_000);
        assertThat(entry.entryDate()).isEqualTo(TODAY);
        assertThat(entry.isReversal()).isFalse();
    }

    @Test
    void debitsMustEqualCredits() {
        assertThatThrownBy(() -> entry(List.of(debit(BANK, 500_000), credit(SAVINGS, 499_999))))
                .extracting("code").isEqualTo("LEDGER_UNBALANCED_ENTRY");
    }

    @Test
    void anEntryNeedsTwoSides() {
        assertThatThrownBy(() -> entry(List.of(debit(BANK, 500_000))))
                .extracting("code").isEqualTo("LEDGER_UNBALANCED_ENTRY");
    }

    @Test
    void amountsMustBePositive() {
        assertThatThrownBy(() -> entry(List.of(debit(BANK, 0), credit(SAVINGS, 0))))
                .extracting("code").isEqualTo("LEDGER_INVALID_ENTRY");
    }

    @Test
    void cannotBeDatedInTheFuture() {
        assertThatThrownBy(() -> JournalEntry.create(CHAMA, TODAY.plusDays(1), "Tomorrow", null,
                PostingRequest.SOURCE_MANUAL, null, null, null,
                List.of(debit(BANK, 100), credit(SAVINGS, 100)), TODAY, NOW))
                .extracting("code").isEqualTo("LEDGER_INVALID_ENTRY");
    }

    @Test
    void reversalMirrorsEveryLineAndPointsBack() {
        JournalEntry original = entry(List.of(debit(BANK, 500_000), memberCredit(SAVINGS, 500_000))).numbered(7);

        JournalEntry reversal = original.reversal("Recorded twice", UUID.randomUUID(), TODAY, NOW);

        assertThat(reversal.isReversal()).isTrue();
        assertThat(reversal.reversesEntryId()).isEqualTo(original.id());
        assertThat(reversal.sourceType()).isEqualTo(PostingRequest.SOURCE_REVERSAL);
        assertThat(reversal.description()).isEqualTo("Reversal of entry #7: Recorded twice");
        assertThat(reversal.lines()).extracting(JournalLine::side).containsExactly(EntrySide.CREDIT, EntrySide.DEBIT);
        assertThat(reversal.lines().get(1).memberUserId()).isEqualTo(MEMBER);
        assertThat(reversal.totalMinor()).isEqualTo(original.totalMinor());
    }

    @Test
    void aReversalNeedsAReason() {
        JournalEntry original = entry(List.of(debit(BANK, 100), credit(SAVINGS, 100))).numbered(1);

        assertThatThrownBy(() -> original.reversal("  ", null, TODAY, NOW))
                .extracting("code").isEqualTo("LEDGER_INVALID_ENTRY");
    }

    @Test
    void balancesFollowTheNormalSide() {
        assertThat(AccountType.ASSET.balance(700, 200)).isEqualTo(500);
        assertThat(AccountType.LIABILITY.balance(200, 700)).isEqualTo(500);
        assertThat(AccountType.INCOME.balance(0, 300)).isEqualTo(300);
        assertThat(AccountType.EXPENSE.balance(300, 0)).isEqualTo(300);
    }

    private static JournalEntry entry(List<JournalLine> lines) {
        return JournalEntry.create(CHAMA, null, "Contribution", "QK7H2XY9ZB", "CONTRIBUTION", "c-1", null, null,
                lines, TODAY, NOW);
    }

    private static JournalLine debit(UUID account, long amount) {
        return new JournalLine(account, EntrySide.DEBIT, amount, null, null);
    }

    private static JournalLine credit(UUID account, long amount) {
        return new JournalLine(account, EntrySide.CREDIT, amount, null, null);
    }

    private static JournalLine memberCredit(UUID account, long amount) {
        return new JournalLine(account, EntrySide.CREDIT, amount, MEMBER, null);
    }
}
