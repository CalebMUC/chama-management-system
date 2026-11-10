package ke.chama.modules.penalty.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Penalties at a glance, for dashboards. Amounts are minor units of the chama's currency. */
public interface PenaltyReports {

    /**
     * What is owed now, and what was assessed, collected and waived between the two days
     * (inclusive): assessed by assessment date, collected by payment date, waived by waiver date.
     *
     * @param topOwing how many members owing the most to list
     */
    PenaltySummary summary(UUID chamaId, LocalDate from, LocalDate to, int topOwing);

    /** One member's penalties: what they owe now, and what they have paid and had waived in all. */
    MemberPenalties ofMember(UUID chamaId, UUID memberUserId);

    /**
     * @param outstandingCount   penalties with something still owed
     * @param membersOwing       members owing at least one penalty
     * @param lateContributionMinor assessed in the range for late contributions
     * @param finesMinor         assessed in the range as fines
     * @param topOwing           members owing the most, most first
     */
    record PenaltySummary(long outstandingMinor,
                          int outstandingCount,
                          int membersOwing,
                          long assessedMinor,
                          int assessedCount,
                          long lateContributionMinor,
                          long finesMinor,
                          long collectedMinor,
                          long waivedMinor,
                          List<MemberOwing> topOwing) {
    }

    record MemberOwing(UUID memberUserId, long outstandingMinor, int penalties) {
    }

    /** @param outstanding the penalties still owed, oldest first */
    record MemberPenalties(long outstandingMinor, long paidMinor, long waivedMinor, int totalCount,
                           List<OwedItem> outstanding) {
    }

    /** @param kind LATE_CONTRIBUTION or FINE */
    record OwedItem(UUID penaltyId, String kind, String description, LocalDate assessedOn, long amountMinor,
                    long outstandingMinor) {
    }
}
