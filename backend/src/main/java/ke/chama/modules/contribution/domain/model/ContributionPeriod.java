package ke.chama.modules.contribution.domain.model;

import ke.chama.modules.contribution.domain.model.PeriodSchedule.Frequency;
import ke.chama.modules.contribution.domain.model.PeriodSchedule.PeriodSpan;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * One contribution cycle of a chama, e.g. "2026-11". {@code amountDueMinor} is what each member
 * owes for it, fixed when the period opens: later settings changes apply to later periods.
 */
public record ContributionPeriod(UUID id,
                                 UUID chamaId,
                                 String key,
                                 Frequency frequency,
                                 LocalDate startDate,
                                 LocalDate endDate,
                                 LocalDate dueDate,
                                 long amountDueMinor,
                                 String currency,
                                 Instant openedAt) {

    public ContributionPeriod {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(chamaId, "chamaId");
        if (amountDueMinor <= 0) {
            throw new IllegalArgumentException("amountDueMinor must be positive");
        }
    }

    public static ContributionPeriod open(UUID chamaId, PeriodSpan span, long amountDueMinor, String currency,
                                          Instant now) {
        return new ContributionPeriod(UUID.randomUUID(), chamaId, span.key(), span.frequency(), span.start(),
                span.end(), span.due(), amountDueMinor, currency, now);
    }

    public boolean isDueBy(LocalDate date) {
        return !dueDate.isAfter(date);
    }

    /** Late once the grace days after the due date have passed. */
    public boolean isLateBy(LocalDate date, int graceDays) {
        return date.isAfter(dueDate.plusDays(Math.max(graceDays, 0)));
    }
}
