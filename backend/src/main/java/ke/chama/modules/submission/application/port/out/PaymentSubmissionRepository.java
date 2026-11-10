package ke.chama.modules.submission.application.port.out;

import ke.chama.modules.submission.domain.model.PaymentSubmission;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Row-level security limits every query to the current chama. */
public interface PaymentSubmissionRepository {

    void insert(PaymentSubmission submission);

    /** Saves the review or cancellation; false if the submission changed since it was read. */
    boolean update(PaymentSubmission submission);

    Optional<PaymentSubmission> submission(UUID chamaId, UUID submissionId);

    /**
     * Every filter is optional except {@code kinds} (an empty collection matches nothing).
     * Pending ones first (oldest first, so the queue is worked in order), then the rest newest first.
     */
    Page<PaymentSubmission> submissions(UUID chamaId, Collection<PaymentSubmission.Kind> kinds,
                                        PaymentSubmission.Status status, UUID memberUserId, Pageable pageable);

    /** A pending or approved submission already carries this reference. */
    boolean referenceTaken(UUID chamaId, String method, String reference);

    Map<PaymentSubmission.Kind, Long> pendingByKind(UUID chamaId);

    long pendingOf(UUID chamaId, UUID memberUserId);
}
