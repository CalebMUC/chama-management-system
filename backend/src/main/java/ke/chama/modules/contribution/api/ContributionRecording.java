package ke.chama.modules.contribution.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Records a contribution for another module, exactly as an official recording it by hand would
 * (same rules: not your own, unique reference, split across the member's products automatically,
 * posted to the ledger, audit-logged, receipt emailed). Runs in the caller's transaction.
 */
public interface ContributionRecording {

    /**
     * @param method CASH, BANK or MPESA
     * @return the recorded payment's id
     */
    UUID record(UUID chamaId, UUID officialUserId, UUID memberUserId, long amountMinor, String method,
                String reference, LocalDate paidOn, String notes);

    /** A recorded (not reversed) contribution already carries this reference. */
    boolean referenceUsed(UUID chamaId, String method, String reference);
}
