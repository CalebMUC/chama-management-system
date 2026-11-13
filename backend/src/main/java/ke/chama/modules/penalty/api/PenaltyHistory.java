package ke.chama.modules.penalty.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Bringing over the penalties and fines of a chama that ran for years before it used the system,
 * as the chama recorded them: each with its payments and, if it was waived, the waiver, posted to
 * the ledger on their own days. Writes run in the caller's transaction; nobody is emailed.
 */
public interface PenaltyHistory {

    /** @param method CASH, BANK or MPESA */
    record HistoricalPayment(LocalDate paidOn, long amountMinor, String method, String reference) {
    }

    /**
     * @param kind      LATE_CONTRIBUTION or FINE
     * @param sourceRef the file's own ref for it; unique per batch
     * @param waivedOn  when what was left unpaid was waived; null if it was not
     */
    record HistoricalPenalty(UUID memberUserId, String kind, String sourceRef, String description, long amountMinor,
                             LocalDate assessedOn, List<HistoricalPayment> payments, LocalDate waivedOn,
                             String waiverReason) {

        public HistoricalPenalty {
            payments = List.copyOf(payments);
        }
    }

    /** References used by penalty payments, as METHOD:REFERENCE upper case. */
    Set<String> usedReferences(UUID chamaId);

    /** Records the penalties, payments and waivers, tagged with the batch. Returns how many penalties. */
    int importPenalties(UUID chamaId, UUID actorUserId, UUID batchId, List<HistoricalPenalty> penalties);

    /** Why the batch's penalties cannot be removed now, e.g. a payment was recorded on one since. */
    Optional<String> undoBlocked(UUID chamaId, UUID batchId);

    /** Removes the batch's penalties and payments, reversing their entries on their own days. */
    int undoPenalties(UUID chamaId, UUID actorUserId, UUID batchId);
}
