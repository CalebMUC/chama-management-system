package ke.chama.modules.contribution.application.port.in;

import ke.chama.modules.contribution.application.port.in.ContributionViews.MyContributionsView;
import ke.chama.modules.contribution.application.port.in.ContributionViews.PaymentView;
import ke.chama.modules.contribution.application.port.in.ContributionViews.PeriodDetailView;
import ke.chama.modules.contribution.application.port.in.ContributionViews.PeriodSummaryView;
import ke.chama.modules.contribution.application.port.in.ContributionViews.StandingView;
import ke.chama.modules.contribution.domain.model.ContributionPayment;
import ke.chama.modules.contribution.domain.model.PaymentMethod;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Contributions of the caller's chama. Periods up to today are opened automatically (and missed
 * ones caught up) whenever contributions are used, so no cycle is ever skipped.
 */
public interface ContributionUseCases {

    /** Records money received from a member and posts it to their savings. Audit-logged. */
    PaymentView record(UUID chamaId, UUID actorUserId, RecordPayment payment);

    /** Reverses a payment and its ledger entry. Audit-logged. */
    PaymentView reverse(UUID chamaId, UUID actorUserId, UUID paymentId, String reason);

    PaymentView payment(UUID chamaId, UUID paymentId);

    Page<PaymentView> payments(UUID chamaId, UUID memberUserId, LocalDate from, LocalDate to,
                               ContributionPayment.Status status, Pageable pageable);

    /** Newest first. */
    List<PeriodSummaryView> periods(UUID chamaId);

    PeriodDetailView period(UUID chamaId, UUID periodId);

    /** Every seated member's standing, most in arrears first. */
    List<StandingView> standings(UUID chamaId, boolean onlyInArrears);

    MyContributionsView mine(UUID chamaId, UUID memberUserId);

    record RecordPayment(UUID memberUserId, long amountMinor, PaymentMethod method, String reference,
                         LocalDate paidOn, String notes) {
    }
}
