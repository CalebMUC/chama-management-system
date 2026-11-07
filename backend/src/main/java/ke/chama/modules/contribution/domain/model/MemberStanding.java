package ke.chama.modules.contribution.domain.model;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Where one member stands: their payments applied to their dues, oldest due first. Nothing is
 * stored per period; a payment simply clears the oldest outstanding amount, and anything paid
 * beyond every due is an advance towards future periods.
 *
 * @param totalPaidMinor  every recorded (not reversed) payment
 * @param dueToDateMinor  dues whose due date has arrived
 * @param arrearsMinor    unpaid part of the dues that have arrived
 * @param advanceMinor    paid beyond every due, including ones not yet due
 */
public record MemberStanding(long totalPaidMinor,
                             long dueToDateMinor,
                             long arrearsMinor,
                             long advanceMinor,
                             int latePeriods,
                             List<PeriodStanding> periods) {

    public enum Status {
        PAID,
        PARTIALLY_PAID,
        UNPAID
    }

    /**
     * @param due  the due date has arrived
     * @param late unpaid, or part-paid, after the grace days
     */
    public record PeriodStanding(ContributionPeriod period,
                                 long amountDueMinor,
                                 long paidMinor,
                                 Status status,
                                 boolean due,
                                 boolean late) {

        public long outstandingMinor() {
            return amountDueMinor - paidMinor;
        }
    }

    /** One member's due for one period. */
    public record Due(ContributionPeriod period, long amountMinor) {
    }

    public long balanceMinor() {
        return totalPaidMinor - dueToDateMinor;
    }

    public static MemberStanding of(List<Due> dues, long totalPaidMinor, LocalDate today, int graceDays) {
        Objects.requireNonNull(dues, "dues");
        List<Due> ordered = dues.stream()
                .sorted(Comparator.comparing((Due d) -> d.period().dueDate()).thenComparing(d -> d.period().startDate()))
                .toList();
        long remaining = Math.max(totalPaidMinor, 0);
        long dueToDate = 0;
        long arrears = 0;
        long allDues = 0;
        int late = 0;
        List<PeriodStanding> periods = new ArrayList<>(ordered.size());
        for (Due due : ordered) {
            long paid = Math.min(remaining, due.amountMinor());
            remaining -= paid;
            allDues += due.amountMinor();
            boolean arrived = due.period().isDueBy(today);
            boolean isLate = paid < due.amountMinor() && due.period().isLateBy(today, graceDays);
            if (arrived) {
                dueToDate += due.amountMinor();
                arrears += due.amountMinor() - paid;
            }
            if (isLate) {
                late++;
            }
            Status status = paid == due.amountMinor() ? Status.PAID : paid > 0 ? Status.PARTIALLY_PAID : Status.UNPAID;
            periods.add(new PeriodStanding(due.period(), due.amountMinor(), paid, status, arrived, isLate));
        }
        long advance = Math.max(totalPaidMinor - allDues, 0);
        return new MemberStanding(totalPaidMinor, dueToDate, arrears, advance, late, List.copyOf(periods));
    }
}
