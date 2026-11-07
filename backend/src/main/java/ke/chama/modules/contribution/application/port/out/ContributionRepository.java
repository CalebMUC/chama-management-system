package ke.chama.modules.contribution.application.port.out;

import ke.chama.modules.contribution.domain.model.ContributionPayment;
import ke.chama.modules.contribution.domain.model.ContributionPeriod;
import ke.chama.modules.contribution.domain.model.PaymentAllocation.Allocation;
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

    /**
     * Opens a period with what each savings product asks for it. Ignored (false) if a period with
     * the same key exists (two requests opening it at once).
     */
    boolean insertPeriod(ContributionPeriod period, Map<UUID, Long> amountPerProduct);

    /** What each product asked for in each period: period id to (product id to amount). */
    Map<UUID, Map<UUID, Long>> periodProducts(UUID chamaId);

    record DueRow(UUID periodId, UUID memberUserId, UUID productId, long amountMinor) {
    }

    List<DueRow> dues(UUID chamaId);

    List<DueRow> duesOf(UUID chamaId, UUID memberUserId);

    /** Existing (period, member, product) dues are skipped. */
    void insertDues(UUID chamaId, Collection<DueRow> dues);

    // ---- payments -----------------------------------------------------------------------------

    /** The payment and how it was split across products. */
    void insertPayment(ContributionPayment payment, List<Allocation> allocations);

    /** Saves the reversal; false if the payment changed since it was read (optimistic lock). */
    boolean updateReversal(ContributionPayment payment);

    Optional<ContributionPayment> payment(UUID chamaId, UUID paymentId);

    /** How each payment was split, by payment id. */
    Map<UUID, List<Allocation>> allocations(UUID chamaId, Collection<UUID> paymentIds);

    boolean recordedReferenceExists(UUID chamaId, PaymentMethod method, String reference);

    /** Every filter is optional. Newest first. */
    Page<ContributionPayment> payments(UUID chamaId, UUID memberUserId, LocalDate from, LocalDate to,
                                       ContributionPayment.Status status, Pageable pageable);

    /** Recorded (not reversed) payments per member and product: member id to (product id to total). */
    Map<UUID, Map<UUID, Long>> paidTotals(UUID chamaId);

    /** One member's recorded payments per product. */
    Map<UUID, Long> paidTotals(UUID chamaId, UUID memberUserId);

    Set<UUID> membersWithPayments(UUID chamaId);
}
