package ke.chama.modules.memberexit.domain.model;

import ke.chama.modules.memberexit.domain.model.Settlement.Draw;
import ke.chama.modules.memberexit.domain.model.Settlement.Kind;
import ke.chama.modules.memberexit.domain.model.Settlement.Line;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettlementTest {

    private static final UUID DEPOSITS = UUID.randomUUID();
    private static final UUID HOLIDAY = UUID.randomUUID();
    private static final UUID SHARES = UUID.randomUUID();
    private static final UUID LOAN = UUID.randomUUID();
    private static final UUID PENALTY = UUID.randomUUID();

    @Test
    void debtsComeOffRefundableSavingsAndNonRefundableSavingsStayWithTheChama() {
        Settlement s = new Settlement("KES", List.of(
                new Line(Kind.REFUND, DEPOSITS, "2000", "Deposits", 2_000_000),
                new Line(Kind.REFUND, HOLIDAY, "2020", "Holiday", 300_000),
                new Line(Kind.FORFEIT, SHARES, "3100", "Share capital", 100_000),
                new Line(Kind.LOAN, LOAN, null, "Loan", 1_100_000),
                new Line(Kind.PENALTY, PENALTY, null, "Fine", 50_000)));

        assertThat(s.refundableMinor()).isEqualTo(2_300_000);
        assertThat(s.forfeitedMinor()).isEqualTo(100_000);
        assertThat(s.owedMinor()).isEqualTo(1_150_000);
        assertThat(s.payoutMinor()).isEqualTo(1_150_000);
        assertThat(s.shortfallMinor()).isZero();
        assertThat(s.offsets()).extracting(o -> o.debt().sourceId()).containsExactly(LOAN, PENALTY);
        assertThat(s.offsets().getFirst().draws()).containsExactly(new Draw("2000", 1_100_000));
        assertThat(s.payoutDraws()).containsExactly(new Draw("2000", 850_000), new Draw("2020", 300_000));
    }

    @Test
    void aDebtLargerThanTheFirstAccountSpillsIntoTheNext() {
        Settlement s = new Settlement("KES", List.of(
                new Line(Kind.REFUND, DEPOSITS, "2000", "Deposits", 1_000_000),
                new Line(Kind.REFUND, HOLIDAY, "2020", "Holiday", 300_000),
                new Line(Kind.LOAN, LOAN, null, "Loan", 1_200_000)));

        assertThat(s.offsets().getFirst().draws())
                .containsExactly(new Draw("2000", 1_000_000), new Draw("2020", 200_000));
        assertThat(s.payoutDraws()).containsExactly(new Draw("2020", 100_000));
        assertThat(s.payoutMinor()).isEqualTo(100_000);
    }

    @Test
    void owingMoreThanTheRefundableSavingsIsAShortfallAndNonRefundableSavingsDoNotCoverIt() {
        Settlement s = new Settlement("KES", List.of(
                new Line(Kind.REFUND, DEPOSITS, "2000", "Deposits", 100_000),
                new Line(Kind.FORFEIT, SHARES, "3100", "Share capital", 900_000),
                new Line(Kind.PENALTY, PENALTY, null, "Fine", 500_000)));

        assertThat(s.shortfallMinor()).isEqualTo(400_000);
        assertThat(s.payoutMinor()).isZero();
        assertThatThrownBy(s::offsets).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void twoSettlementsAreTheSameWhenEveryLineMatches() {
        Settlement a = new Settlement("KES", List.of(new Line(Kind.REFUND, DEPOSITS, "2000", "Deposits", 100_000),
                new Line(Kind.PENALTY, PENALTY, null, "Fine", 5_000)));
        Settlement reordered = new Settlement("KES", List.of(new Line(Kind.PENALTY, PENALTY, null, "Fine (renamed)", 5_000),
                new Line(Kind.REFUND, DEPOSITS, "2000", "Deposits", 100_000)));
        Settlement moreSaved = new Settlement("KES", List.of(new Line(Kind.REFUND, DEPOSITS, "2000", "Deposits", 150_000),
                new Line(Kind.PENALTY, PENALTY, null, "Fine", 5_000)));

        assertThat(a.sameAs(reordered)).isTrue();
        assertThat(a.sameAs(moreSaved)).isFalse();
    }
}
