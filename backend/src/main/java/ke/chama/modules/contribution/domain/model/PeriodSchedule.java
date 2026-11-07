package ke.chama.modules.contribution.domain.model;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;

/**
 * Turns a chama's contribution rules into periods: calendar months (due on day 1-28) or ISO weeks
 * Monday to Sunday (due on day 1-7, 1 = Monday).
 */
public final class PeriodSchedule {

    private PeriodSchedule() {
    }

    /** The span of one period, before it is opened. */
    public record PeriodSpan(String key, Frequency frequency, LocalDate start, LocalDate end, LocalDate due) {
    }

    public enum Frequency {
        MONTHLY,
        WEEKLY
    }

    /** The period that contains {@code date}. */
    public static PeriodSpan containing(LocalDate date, Frequency frequency, int dueDay) {
        return switch (frequency) {
            case MONTHLY -> {
                YearMonth month = YearMonth.from(date);
                yield new PeriodSpan(month.toString(), frequency, month.atDay(1), month.atEndOfMonth(),
                        month.atDay(Math.clamp(dueDay, 1, 28)));
            }
            case WEEKLY -> {
                LocalDate monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                String key = "%d-W%02d".formatted(monday.get(IsoFields.WEEK_BASED_YEAR),
                        monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
                yield new PeriodSpan(key, frequency, monday, monday.plusDays(6),
                        monday.plusDays(Math.clamp(dueDay, 1, 7) - 1L));
            }
        };
    }

    /**
     * The period right after one that ends on {@code previousEnd}. If the frequency changed in
     * between, the new period starts the day after, so periods never overlap.
     */
    public static PeriodSpan after(LocalDate previousEnd, Frequency frequency, int dueDay) {
        LocalDate start = previousEnd.plusDays(1);
        PeriodSpan span = containing(start, frequency, dueDay);
        if (span.start().isBefore(start)) {
            LocalDate due = span.due().isBefore(start) ? start : span.due();
            return new PeriodSpan(span.key(), frequency, start, span.end(), due);
        }
        return span;
    }
}
