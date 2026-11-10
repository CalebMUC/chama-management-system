package ke.chama.modules.contribution.application.service;

import ke.chama.modules.contribution.api.ContributionRecording;
import ke.chama.modules.contribution.application.port.in.ContributionUseCases;
import ke.chama.modules.contribution.application.port.in.ContributionUseCases.RecordPayment;
import ke.chama.modules.contribution.application.port.out.ContributionRepository;
import ke.chama.modules.contribution.domain.model.PaymentMethod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

/** {@link ContributionRecording} over the same use case officials call. */
@Service
@Transactional
class ContributionRecordingAdapter implements ContributionRecording {

    private final ContributionUseCases contributions;
    private final ContributionRepository repository;

    ContributionRecordingAdapter(ContributionUseCases contributions, ContributionRepository repository) {
        this.contributions = contributions;
        this.repository = repository;
    }

    @Override
    public UUID record(UUID chamaId, UUID officialUserId, UUID memberUserId, long amountMinor, String method,
                       String reference, LocalDate paidOn, String notes) {
        return contributions.record(chamaId, officialUserId, new RecordPayment(memberUserId, amountMinor,
                PaymentMethod.valueOf(method), reference, paidOn, notes)).id();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean referenceUsed(UUID chamaId, String method, String reference) {
        return reference != null && repository.recordedReferenceExists(chamaId, PaymentMethod.valueOf(method),
                reference.trim().toUpperCase(Locale.ROOT));
    }
}
