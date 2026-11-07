package ke.chama.modules.penalty.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Penalties and fines still owed when a chama moves over from its earlier records (spreadsheets).
 * Runs in the caller's transaction. The caller posts the opening-balance ledger entry; the
 * penalty only records it.
 */
public interface PenaltyImport {

    /** One outstanding penalty per member, "brought forward", that can be paid or waived like any other. */
    UUID importOutstanding(UUID chamaId, UUID memberUserId, long amountMinor, String currency, LocalDate asOf,
                           UUID importedBy, UUID openingEntryId);
}
