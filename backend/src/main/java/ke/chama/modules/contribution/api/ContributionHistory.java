package ke.chama.modules.contribution.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Bringing over the contribution history of a chama that ran for years before it used the system.
 * Writes run in the caller's transaction; nothing here emails anyone.
 * <ol>
 *   <li>{@link #backfill}: the periods before the chama's first period in the system, each asking
 *   what the chama's schedule says it asked then. They are marked historical: never penalised
 *   automatically.</li>
 *   <li>{@link #importPayments}: the payments, in date order, each split across the member's
 *   products exactly as recording it by hand would, and posted to the ledger on the day it was paid.</li>
 * </ol>
 */
public interface ContributionHistory {

    /**
     * What one savings product asked per period from {@code effectiveFrom} until its next change.
     * It applies to the periods due on or after that day. Zero means the product asked nothing.
     */
    record ScheduledAmount(UUID productId, LocalDate effectiveFrom, long amountMinor) {
    }

    /** A period {@link #backfill} would create. */
    record PlannedPeriod(String key, LocalDate startDate, LocalDate endDate, LocalDate dueDate, long amountDueMinor,
                         Map<UUID, Long> amountPerProduct) {
    }

    /**
     * The periods back-filling would create, from the one containing the schedule's earliest day up
     * to the chama's first period in the system (or the current one, if none is open yet). Periods
     * the default product asks nothing for (before the chama started) are left out. Reads only.
     */
    List<PlannedPeriod> plannedPeriods(UUID chamaId, List<ScheduledAmount> schedule);

    /**
     * Creates the planned periods, tagged with the batch, then gives every member a due in each for
     * every account open on its due date. Returns how many periods were created.
     */
    int backfill(UUID chamaId, List<ScheduledAmount> schedule, UUID batchId);

    /**
     * Undoes {@link #backfill}: deletes the batch's periods and their dues, then, for the given
     * member accounts, the dues for periods due before the account's opened date (call it after
     * restoring the dates the setup moved back).
     */
    void removeBackfill(UUID chamaId, UUID batchId, java.util.Collection<UUID> restoredAccountIds);

    /** Whether any period was back-filled (a setup import is in place). */
    boolean hasHistoricalPeriods(UUID chamaId);

    /**
     * @param method    CASH, BANK or MPESA
     * @param productId the savings product it all went to; null to split it as the system would
     */
    record HistoricalPayment(UUID memberUserId, long amountMinor, String method, String reference, LocalDate paidOn,
                             String notes, UUID productId) {
    }

    /**
     * Records the payments oldest first, as an official recording each by hand would (same
     * validation and split, a ledger entry dated the day it was paid), tagged with the batch. No
     * receipts are emailed; one audit entry covers the batch. Returns how many were recorded.
     */
    int importPayments(UUID chamaId, UUID actorUserId, UUID batchId, List<HistoricalPayment> payments);

    /** Reverses the batch's payments, each reversal dated as its payment was. Returns how many. */
    int undoPayments(UUID chamaId, UUID actorUserId, UUID batchId);

    /** References already carried by recorded payments, as METHOD:REFERENCE in upper case. */
    Set<String> usedReferences(UUID chamaId);

    /**
     * Where each seated member stands today without anything being opened or changed: what has
     * fallen due across every product and what was paid. Members with neither are absent.
     */
    Map<UUID, Standing> standings(UUID chamaId);

    record Standing(long dueToDateMinor, long paidMinor) {
        /** Paid less due: negative means arrears, positive is paid in advance. */
        public long balanceMinor() {
            return paidMinor - dueToDateMinor;
        }
    }
}
