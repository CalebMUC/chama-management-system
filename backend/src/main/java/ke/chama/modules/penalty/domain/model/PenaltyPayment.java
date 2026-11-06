package ke.chama.modules.penalty.domain.model;

import ke.chama.modules.penalty.domain.exception.PenaltyExceptions.InvalidPenaltyException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

/**
 * A payment towards one penalty, posted to the ledger.
 *
 * @param method CASH, BANK or MPESA; M-Pesa and bank payments carry their reference
 */
public record PenaltyPayment(UUID id,
                             UUID chamaId,
                             UUID penaltyId,
                             UUID memberUserId,
                             long amountMinor,
                             String currency,
                             String method,
                             String reference,
                             LocalDate paidOn,
                             UUID ledgerEntryId,
                             UUID recordedBy,
                             Instant recordedAt) {

    public static PenaltyPayment record(Penalty penalty, long amountMinor, String method, String reference,
                                        LocalDate paidOn, UUID recordedBy, LocalDate today, Instant now) {
        if (method == null || !(method.equals("CASH") || method.equals("BANK") || method.equals("MPESA"))) {
            throw new InvalidPenaltyException("method must be CASH, BANK or MPESA");
        }
        LocalDate date = paidOn == null ? today : paidOn;
        if (date.isAfter(today)) {
            throw new InvalidPenaltyException("A payment cannot be dated in the future");
        }
        String ref = reference == null || reference.isBlank() ? null : reference.trim().toUpperCase(Locale.ROOT);
        if (ref == null && !method.equals("CASH")) {
            throw new InvalidPenaltyException(method + " payments need their reference (transaction code or slip number)");
        }
        if (ref != null && ref.length() > 100) {
            throw new InvalidPenaltyException("reference must be at most 100 characters");
        }
        return new PenaltyPayment(UUID.randomUUID(), penalty.chamaId(), penalty.id(), penalty.memberUserId(),
                amountMinor, penalty.currency(), method, ref, date, null, recordedBy, now);
    }

    public PenaltyPayment postedAs(UUID entryId) {
        return new PenaltyPayment(id, chamaId, penaltyId, memberUserId, amountMinor, currency, method, reference,
                paidOn, entryId, recordedBy, recordedAt);
    }
}
