package ke.chama.modules.penalty.application.port.in;

import ke.chama.modules.penalty.domain.model.Penalty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Penalties of the caller's chama. Late-contribution penalties are assessed automatically (by a
 * nightly job, and whenever penalties are listed); every step posts to the ledger.
 */
public interface PenaltyUseCases {

    /** Penalises every late contribution not penalised yet. Returns the new penalties. */
    List<PenaltyView> assessLateContributions(UUID chamaId);

    PenaltyView fine(UUID chamaId, UUID actorUserId, UUID memberUserId, long amountMinor, String reason);

    PenaltyView recordPayment(UUID chamaId, UUID actorUserId, UUID penaltyId, PaymentRequest payment);

    PenaltyView waive(UUID chamaId, UUID actorUserId, UUID penaltyId, String reason);

    PenaltyDetailView penalty(UUID chamaId, UUID penaltyId);

    Page<PenaltyView> penalties(UUID chamaId, UUID memberUserId, Penalty.Status status, Pageable pageable);

    MyPenaltiesView mine(UUID chamaId, UUID memberUserId);

    record PaymentRequest(long amountMinor, String method, String reference, LocalDate paidOn) {
    }

    /** Amounts are minor units (cents) of {@code currency}. {@code assessedBy} is null for automatic penalties. */
    record PenaltyView(UUID id,
                       UUID memberUserId,
                       String memberName,
                       Integer memberNumber,
                       Penalty.Kind kind,
                       String description,
                       long amountMinor,
                       long paidMinor,
                       long waivedMinor,
                       long outstandingMinor,
                       String currency,
                       Penalty.Status status,
                       LocalDate assessedOn,
                       UUID assessedBy,
                       UUID waivedBy,
                       Instant waivedAt,
                       String waiverReason) {
    }

    record PenaltyPaymentView(UUID id, long amountMinor, String method, String reference, LocalDate paidOn,
                              UUID ledgerEntryId, UUID recordedBy, Instant recordedAt) {
    }

    record PenaltyDetailView(PenaltyView penalty, List<PenaltyPaymentView> payments) {
    }

    record MyPenaltiesView(String currency, long outstandingMinor, List<PenaltyView> penalties) {
    }
}
