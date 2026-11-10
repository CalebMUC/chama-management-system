package ke.chama.modules.penalty.api;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Penalty payments for another module, recorded exactly as an official recording one by hand
 * would (not your own penalty, at most what is outstanding, unique reference, posted to the
 * ledger, audit-logged). Runs in the caller's transaction.
 */
public interface PenaltyPayments {

    /** The penalty as it stands, if it exists in the chama. */
    Optional<OwedPenalty> penalty(UUID chamaId, UUID penaltyId);

    /** @param method CASH, BANK or MPESA */
    void recordPayment(UUID chamaId, UUID officialUserId, UUID penaltyId, long amountMinor, String method,
                       String reference, LocalDate paidOn);

    /** A penalty payment already carries this reference. */
    boolean referenceUsed(UUID chamaId, String method, String reference);

    /**
     * @param status OUTSTANDING, PAID or WAIVED
     */
    record OwedPenalty(UUID id, UUID memberUserId, String description, String status, long outstandingMinor,
                       String currency) {

        public boolean outstanding() {
            return "OUTSTANDING".equals(status);
        }
    }
}
