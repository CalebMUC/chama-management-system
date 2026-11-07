package ke.chama.modules.contribution.domain.model;

import ke.chama.modules.contribution.domain.model.PeriodSchedule.Frequency;
import ke.chama.modules.contribution.domain.model.PeriodSchedule.PeriodSpan;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class PeriodScheduleTest {

    @Test
    void monthlyPeriodsAreCalendarMonthsDueOnTheDueDay() {
        PeriodSpan november = PeriodSchedule.containing(LocalDate.of(2026, 11, 20), Frequency.MONTHLY, 5);

        assertThat(november.key()).isEqualTo("2026-11");
        assertThat(november.start()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(november.end()).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(november.due()).isEqualTo(LocalDate.of(2026, 11, 5));
    }

    @Test
    void theNextMonthFollowsWithoutGaps() {
        PeriodSpan december = PeriodSchedule.after(LocalDate.of(2026, 11, 30), Frequency.MONTHLY, 5);
        PeriodSpan january = PeriodSchedule.after(december.end(), Frequency.MONTHLY, 5);

        assertThat(december.key()).isEqualTo("2026-12");
        assertThat(january.key()).isEqualTo("2027-01");
        assertThat(january.start()).isEqualTo(LocalDate.of(2027, 1, 1));
    }

    @Test
    void weeklyPeriodsAreIsoWeeksDueOnTheDueWeekday() {
        // Friday 6 November 2026 is in ISO week 45, which starts on Monday 2 November.
        PeriodSpan week = PeriodSchedule.containing(LocalDate.of(2026, 11, 6), Frequency.WEEKLY, 3);

        assertThat(week.key()).isEqualTo("2026-W45");
        assertThat(week.start()).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(week.end()).isEqualTo(LocalDate.of(2026, 11, 8));
        assertThat(week.due()).isEqualTo(LocalDate.of(2026, 11, 4));   // Wednesday
    }

    @Test
    void weeksAcrossTheYearEndUseTheIsoWeekYear() {
        PeriodSpan week = PeriodSchedule.containing(LocalDate.of(2026, 12, 31), Frequency.WEEKLY, 1);

        assertThat(week.key()).isEqualTo("2026-W53");
        assertThat(PeriodSchedule.after(week.end(), Frequency.WEEKLY, 1).key()).isEqualTo("2027-W01");
    }

    @Test
    void switchingFrequencyNeverOverlapsThePreviousPeriod() {
        // Monthly November ends on Monday 30th; the first weekly period starts the next day.
        PeriodSpan firstWeek = PeriodSchedule.after(LocalDate.of(2026, 11, 30), Frequency.WEEKLY, 1);

        assertThat(firstWeek.start()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(firstWeek.due()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(firstWeek.end()).isEqualTo(LocalDate.of(2026, 12, 6));
    }
}
