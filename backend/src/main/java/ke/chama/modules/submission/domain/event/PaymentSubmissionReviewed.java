package ke.chama.modules.submission.domain.event;

import ke.chama.shared.domain.DomainEvent;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * An official approved or rejected a payment a member reported. The notification module tells
 * the member.
 *
 * @param kind     CONTRIBUTION, PENALTY_PAYMENT or LOAN_REPAYMENT
 * @param approved true if confirmed and recorded; false if rejected
 * @param reason   the rejection reason, or the approver's optional note
 */
public record PaymentSubmissionReviewed(UUID eventId,
                                        Instant occurredAt,
                                        UUID chamaId,
                                        String chamaName,
                                        UUID submissionId,
                                        UUID memberUserId,
                                        String memberEmail,
                                        String memberFirstName,
                                        String kind,
                                        long amountMinor,
                                        String currency,
                                        String method,
                                        String reference,
                                        LocalDate paidOn,
                                        boolean approved,
                                        String reason) implements DomainEvent {
}
