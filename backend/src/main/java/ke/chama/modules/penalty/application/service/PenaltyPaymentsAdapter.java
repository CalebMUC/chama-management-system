package ke.chama.modules.penalty.application.service;

import ke.chama.modules.penalty.api.PenaltyPayments;
import ke.chama.modules.penalty.application.port.in.PenaltyUseCases;
import ke.chama.modules.penalty.application.port.in.PenaltyUseCases.PaymentRequest;
import ke.chama.modules.penalty.application.port.out.PenaltyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** {@link PenaltyPayments} over the same use case officials call. */
@Service
@Transactional
class PenaltyPaymentsAdapter implements PenaltyPayments {

    private final PenaltyUseCases penalties;
    private final PenaltyRepository repository;

    PenaltyPaymentsAdapter(PenaltyUseCases penalties, PenaltyRepository repository) {
        this.penalties = penalties;
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OwedPenalty> penalty(UUID chamaId, UUID penaltyId) {
        return repository.penalty(chamaId, penaltyId).map(p -> new OwedPenalty(p.id(), p.memberUserId(),
                p.description(), p.status().name(), p.outstandingMinor(), p.currency()));
    }

    @Override
    public void recordPayment(UUID chamaId, UUID officialUserId, UUID penaltyId, long amountMinor, String method,
                              String reference, LocalDate paidOn) {
        penalties.recordPayment(chamaId, officialUserId, penaltyId, new PaymentRequest(amountMinor, method, reference, paidOn));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean referenceUsed(UUID chamaId, String method, String reference) {
        return reference != null && repository.referenceExists(chamaId, method, reference.trim().toUpperCase(Locale.ROOT));
    }
}
