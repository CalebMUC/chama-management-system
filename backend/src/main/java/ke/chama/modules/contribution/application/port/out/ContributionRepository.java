package ke.chama.modules.contribution.application.port.out;

import ke.chama.modules.contribution.domain.model.ContributionPayment;
import ke.chama.modules.contribution.domain.model.ContributionPeriod;
import ke.chama.modules.contribution.domain.model.PaymentMethod;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Row-level security limits every query to the current chama. */
public interface ContributionRepository {

    // ---- periods and dues ---------------------------------------------------------------------

    Optional<ContributionPeriod> latestPeriod(UUID chamaId);

    /** Oldest first. */
    List<ContributionPeriod> periods(UUID chamaId);

    Optional<ContributionPeriod> period(UUID chamaId, UUID periodId);

    /** Ignored if a period with the same key exists (two requests opening it at once). */
    void insertPeriod(ContributionPeriod period);

    record DueRow(UUID periodId, UUID memberUserId, long amountMinor) {
    }

    List<DueRow> dues(UUID chamaId);

    List<DueRow> duesOf(UUID chamaId, UUID memberUserId);

    /** Existing (periodId, memberUserId) pairs are skipped. */
    void insertDues(UUID chamaId, Collection<DueRow> dues);

    // ---- payments -----------------------------------------------------------------------------

    void insertPayment(ContributionPayment payment);

    /** Saves the reversal; false if the payment changed since it was read (optimistic lock). */
    boolean updateReversal(ContributionPayment payment);

    Optional<ContributionPayment> payment(UUID chamaId, UUID paymentId);

    boolean recordedReferenceExists(UUID chamaId, PaymentMethod method, String reference);

    /** Every filter is optional. Newest first. */
    Page<ContributionPayment> payments(UUID chamaId, UUID memberUserId, LocalDate from, LocalDate to,
                                       ContributionPayment.Status status, Pageable pageable);

    /** Sum of recorded (not reversed) payments per member. */
    Map<UUID, Long> paidTotals(UUID chamaId);

    long paidTotal(UUID chamaId, UUID memberUserId);

    Set<UUID> membersWithPayments(UUID chamaId);
}
