package ke.chama.modules.contribution.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Where the chama's contributions stand, for reports and dashboards. Both methods bring periods and
 * dues up to date first, so they need a (write) transaction.
 */
public interface ContributionReports {

    /** Every period (oldest first) against every seated member, with totals both ways. */
    ContributionMatrix matrix(UUID chamaId);

    /** One seated member's standing across every savings product. */
    StandingSnapshot standing(UUID chamaId, UUID memberUserId);

    /**
     * @param periods oldest first
     * @param members by member number; each row's cells line up with {@code periods}
     */
    record ContributionMatrix(String currency, List<PeriodColumn> periods, List<MemberRow> members) {
    }

    /**
     * @param due            the due date has arrived
     * @param expectedMinor  owed for the period by every member
     * @param collectedMinor paid towards it (payments clear the oldest dues first)
     */
    record PeriodColumn(UUID periodId, String key, LocalDate dueDate, boolean due, long expectedMinor,
                        long collectedMinor, int members, int membersPaid, int membersLate) {
    }

    /** @param cells one per period, in the same order; null where the member owed nothing for it */
    record MemberRow(UUID memberUserId, StandingSnapshot standing, List<Cell> cells) {
    }

    /** @param status PAID, PARTIALLY_PAID or UNPAID */
    record Cell(long dueMinor, long paidMinor, String status, boolean late) {
    }

    /**
     * @param arrearsMinor        unpaid part of the dues that have arrived
     * @param advanceMinor        paid beyond every due
     * @param latestPeriod        the latest period the member owes for, e.g. "2026-11"; null if none
     * @param latestPeriodStatus  PAID, PARTIALLY_PAID or UNPAID for that period; null if none
     */
    record StandingSnapshot(long totalPaidMinor, long dueToDateMinor, long arrearsMinor, long advanceMinor,
                            int latePeriods, String latestPeriod, String latestPeriodStatus) {
    }
}
