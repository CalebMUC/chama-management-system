package ke.chama.modules.contribution.domain.model;

import ke.chama.modules.contribution.domain.model.MemberStanding.Due;
import ke.chama.modules.contribution.domain.model.MemberStanding.Status;
import ke.chama.modules.contribution.domain.model.PeriodSchedule.Frequency;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MemberStandingTest {

    private static final UUID CHAMA = UUID.randomUUID();
    private static final long KES_5000 = 500_000;
    private static final ContributionPeriod SEPTEMBER = period("2026-09", LocalDate.of(2026, 9, 5));
    private static final ContributionPeriod OCTOBER = period("2026-10", LocalDate.of(2026, 10, 5));
    private static final ContributionPeriod NOVEMBER = period("2026-11", LocalDate.of(2026, 11, 5));
    private static final ContributionPeriod DECEMBER = period("2026-12", LocalDate.of(2026, 12, 5));
    private static final List<Due> FOUR_MONTHS = List.of(
            new Due(NOVEMBER, KES_5000), new Due(SEPTEMBER, KES_5000), new Due(DECEMBER, KES_5000), new Due(OCTOBER, KES_5000));

    @Test
    void paymentsClearTheOldestDuesFirst() {
        MemberStanding standing = MemberStanding.of(FOUR_MONTHS, 750_000, LocalDate.of(2026, 11, 20), 5);

        assertThat(standing.periods()).extracting(p -> p.period().key())
                .containsExactly("2026-09", "2026-10", "2026-11", "2026-12");
        assertThat(standing.periods()).extracting(MemberStanding.PeriodStanding::status)
                .containsExactly(Status.PAID, Status.PARTIALLY_PAID, Status.UNPAID, Status.UNPAID);
        assertThat(standing.periods().get(1).paidMinor()).isEqualTo(250_000);
    }

    @Test
    void arrearsCountOnlyDuesThatHaveArrived() {
        MemberStanding standing = MemberStanding.of(FOUR_MONTHS, 750_000, LocalDate.of(2026, 11, 20), 5);

        assertThat(standing.dueToDateMinor()).isEqualTo(3 * KES_5000);   // December is not due yet
        assertThat(standing.arrearsMinor()).isEqualTo(750_000);
        assertThat(standing.balanceMinor()).isEqualTo(-750_000);
        assertThat(standing.advanceMinor()).isZero();
    }

    @Test
    void lateMeansUnpaidAfterTheGraceDays() {
        // 8 November: November (due on the 5th, 5 grace days) is not late yet; October is.
        MemberStanding standing = MemberStanding.of(FOUR_MONTHS, 750_000, LocalDate.of(2026, 11, 8), 5);

        assertThat(standing.periods()).extracting(MemberStanding.PeriodStanding::late)
                .containsExactly(false, true, false, false);
        assertThat(standing.latePeriods()).isEqualTo(1);

        MemberStanding later = MemberStanding.of(FOUR_MONTHS, 750_000, LocalDate.of(2026, 11, 11), 5);
        assertThat(later.latePeriods()).isEqualTo(2);
    }

    @Test
    void payingAheadIsAnAdvance() {
        MemberStanding standing = MemberStanding.of(FOUR_MONTHS, 5 * KES_5000, LocalDate.of(2026, 11, 20), 5);

        assertThat(standing.periods()).allMatch(p -> p.status() == Status.PAID);
        assertThat(standing.arrearsMinor()).isZero();
        assertThat(standing.advanceMinor()).isEqualTo(KES_5000);
        assertThat(standing.balanceMinor()).isEqualTo(2 * KES_5000);
    }

    @Test
    void aMemberWithNoDuesStandsAtZero() {
        MemberStanding standing = MemberStanding.of(List.of(), 0, LocalDate.of(2026, 11, 20), 5);

        assertThat(standing.arrearsMinor()).isZero();
        assertThat(standing.periods()).isEmpty();
    }

    @Test
    void standingsPerProductAddUpPeriodByPeriod() {
        LocalDate today = LocalDate.of(2026, 11, 20);
        // Deposits: KES 5,000 a month, fully paid. Shares: KES 500 a month, only September paid.
        MemberStanding deposits = MemberStanding.of(List.of(new Due(SEPTEMBER, KES_5000), new Due(OCTOBER, KES_5000)),
                2 * KES_5000, today, 5);
        MemberStanding shares = MemberStanding.of(List.of(new Due(SEPTEMBER, 50_000), new Due(OCTOBER, 50_000)),
                50_000, today, 5);

        MemberStanding all = MemberStanding.combine(List.of(deposits, shares));

        assertThat(all.totalPaidMinor()).isEqualTo(2 * KES_5000 + 50_000);
        assertThat(all.dueToDateMinor()).isEqualTo(2 * KES_5000 + 100_000);
        assertThat(all.arrearsMinor()).isEqualTo(50_000);
        assertThat(all.periods()).extracting(p -> p.period().key()).containsExactly("2026-09", "2026-10");
        assertThat(all.periods()).extracting(MemberStanding.PeriodStanding::amountDueMinor)
                .containsExactly(550_000L, 550_000L);
        assertThat(all.periods()).extracting(MemberStanding.PeriodStanding::status)
                .containsExactly(Status.PAID, Status.PARTIALLY_PAID);
        assertThat(all.periods().get(1).late()).isTrue();
        assertThat(all.latePeriods()).isEqualTo(1);
    }

    @Test
    void oneProductCombinesToItself() {
        MemberStanding only = MemberStanding.of(FOUR_MONTHS, 750_000, LocalDate.of(2026, 11, 20), 5);

        assertThat(MemberStanding.combine(List.of(only))).isEqualTo(only);
        assertThat(MemberStanding.combine(List.of()).arrearsMinor()).isZero();
    }

    private static ContributionPeriod period(String key, LocalDate due) {
        return new ContributionPeriod(UUID.randomUUID(), CHAMA, key, Frequency.MONTHLY, due.withDayOfMonth(1),
                due.withDayOfMonth(due.lengthOfMonth()), due, KES_5000, "KES", Instant.EPOCH);
    }
}
