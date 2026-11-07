package ke.chama.modules.contribution.domain.model;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

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

    /**
     * One member's overall standing from their standing in each savings product: totals add up,
     * and each period's due and paid amounts add up across products. A period is late if any
     * product's due for it is late.
     */
    public static MemberStanding combine(List<MemberStanding> perProduct) {
        Objects.requireNonNull(perProduct, "perProduct");
        if (perProduct.size() == 1) {
            return perProduct.getFirst();
        }
        long paid = 0;
        long dueToDate = 0;
        long arrears = 0;
        long advance = 0;
        Map<UUID, PeriodStanding> byPeriod = new LinkedHashMap<>();
        for (MemberStanding s : perProduct) {
            paid += s.totalPaidMinor();
            dueToDate += s.dueToDateMinor();
            arrears += s.arrearsMinor();
            advance += s.advanceMinor();
            for (PeriodStanding ps : s.periods()) {
                byPeriod.merge(ps.period().id(), ps, (a, b) -> {
                    long due = a.amountDueMinor() + b.amountDueMinor();
                    long paidForPeriod = a.paidMinor() + b.paidMinor();
                    Status status = paidForPeriod == due ? Status.PAID : paidForPeriod > 0 ? Status.PARTIALLY_PAID : Status.UNPAID;
                    return new PeriodStanding(a.period(), due, paidForPeriod, status, a.due() || b.due(), a.late() || b.late());
                });
            }
        }
        List<PeriodStanding> periods = byPeriod.values().stream()
                .sorted(Comparator.comparing((PeriodStanding ps) -> ps.period().dueDate())
                        .thenComparing(ps -> ps.period().startDate()))
                .toList();
        int late = (int) periods.stream().filter(PeriodStanding::late).count();
        return new MemberStanding(paid, dueToDate, arrears, advance, late, periods);
    }
}
