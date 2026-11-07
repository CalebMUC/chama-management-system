package ke.chama.modules.penalty.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PenaltyTest {

    private static final UUID CHAMA = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID PERIOD = UUID.randomUUID();
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);
    private static final Instant NOW = Instant.parse("2026-11-20T09:00:00Z");

    @Test
    void theLatePenaltyIsTheRateOfWhatIsUnpaid() {
        Penalty penalty = late(500_000, 200);

        assertThat(penalty.amountMinor()).isEqualTo(10_000);   // 2% of KES 5,000 = KES 100
        assertThat(penalty.kind()).isEqualTo(Penalty.Kind.LATE_CONTRIBUTION);
        assertThat(penalty.sourceKey()).isEqualTo("LATE_CONTRIBUTION:" + PERIOD);
        assertThat(penalty.description()).isEqualTo("Late contribution for 2026-10: 2% of KES 5,000.00 unpaid");
        assertThat(penalty.status()).isEqualTo(Penalty.Status.OUTSTANDING);
    }

    @Test
    void amountsRoundHalfUpAndNothingIsChargedAtZero() {
        assertThat(late(250_025, 200).amountMinor()).isEqualTo(5_001);   // 5000.5 rounds up
        assertThat(Penalty.forLateContribution(CHAMA, MEMBER, PERIOD, "2026-10", 500_000, "KES", 0, TODAY, NOW)).isEmpty();
        assertThat(Penalty.forLateContribution(CHAMA, MEMBER, PERIOD, "2026-10", 0, "KES", 200, TODAY, NOW)).isEmpty();
    }

    @Test
    void paidInPartsUntilNothingIsOutstanding() {
        Penalty penalty = late(500_000, 200);

        penalty.pay(4_000);
        assertThat(penalty.outstandingMinor()).isEqualTo(6_000);
        assertThat(penalty.status()).isEqualTo(Penalty.Status.OUTSTANDING);

        assertThatThrownBy(() -> penalty.pay(6_001)).extracting("code").isEqualTo("PENALTY_INVALID");
        penalty.pay(6_000);
        assertThat(penalty.status()).isEqualTo(Penalty.Status.PAID);
        assertThatThrownBy(() -> penalty.pay(1)).extracting("code").isEqualTo("PENALTY_CLOSED");
    }

    @Test
    void waivingClearsWhatIsLeftAndNeedsAReason() {
        Penalty penalty = late(500_000, 200);
        penalty.pay(4_000);

        assertThatThrownBy(() -> penalty.waive(UUID.randomUUID(), " ", NOW)).extracting("code").isEqualTo("PENALTY_INVALID");
        long waived = penalty.waive(UUID.randomUUID(), "First offence", NOW);

        assertThat(waived).isEqualTo(6_000);
        assertThat(penalty.status()).isEqualTo(Penalty.Status.WAIVED);
        assertThat(penalty.outstandingMinor()).isZero();
        assertThat(penalty.paidMinor()).isEqualTo(4_000);
    }

    @Test
    void finesNeedAnAmountAndAReason() {
        assertThatThrownBy(() -> Penalty.fine(CHAMA, MEMBER, 0, "KES", "Missed meeting", null, TODAY, NOW))
                .extracting("code").isEqualTo("PENALTY_INVALID");
        assertThatThrownBy(() -> Penalty.fine(CHAMA, MEMBER, 50_000, "KES", "", null, TODAY, NOW))
                .extracting("code").isEqualTo("PENALTY_INVALID");
        assertThat(Penalty.fine(CHAMA, MEMBER, 50_000, "KES", " Missed meeting ", null, TODAY, NOW).description())
                .isEqualTo("Missed meeting");
    }

    private static Penalty late(long outstanding, int basisPoints) {
        return Penalty.forLateContribution(CHAMA, MEMBER, PERIOD, "2026-10", outstanding, "KES", basisPoints, TODAY, NOW)
                .orElseThrow();
    }
}
