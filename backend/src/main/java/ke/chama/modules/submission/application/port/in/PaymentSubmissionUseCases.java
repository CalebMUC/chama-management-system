package ke.chama.modules.submission.application.port.in;

import ke.chama.modules.submission.domain.model.PaymentSubmission;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Payments members report, and the officials' review of them. A member reports; an official
 * with the matching record permission approves (which records the real payment) or rejects.
 */
public interface PaymentSubmissionUseCases {

    /** The caller reports a payment they made. Checked against the penalty or loan it is for. */
    SubmissionView submit(UUID chamaId, UUID memberUserId, NewSubmission submission);

    /** The submitter withdraws a pending report. */
    SubmissionView cancel(UUID chamaId, UUID actorUserId, UUID submissionId);

    /** Confirms the payment and records it as a contribution, penalty payment or loan repayment. */
    SubmissionView approve(UUID chamaId, UUID officialUserId, UUID submissionId, String note);

    SubmissionView reject(UUID chamaId, UUID officialUserId, UUID submissionId, String reason);

    /** One submission: to its submitter, or to an official who may review its kind. */
    SubmissionView submission(UUID chamaId, UUID callerUserId, UUID submissionId);

    /**
     * Submissions of the kinds the official may review. Pending first (oldest first), then the
     * rest newest first.
     */
    Page<SubmissionView> reviewQueue(UUID chamaId, UUID officialUserId, PaymentSubmission.Status status,
                                     PaymentSubmission.Kind kind, UUID memberUserId, Pageable pageable);

    /** The caller's own reports, pending first. */
    Page<SubmissionView> mine(UUID chamaId, UUID memberUserId, PaymentSubmission.Status status, Pageable pageable);

    /**
     * @param targetId the penalty (PENALTY_PAYMENT) or loan (LOAN_REPAYMENT) paid towards; null for a contribution
     */
    record NewSubmission(PaymentSubmission.Kind kind, UUID targetId, long amountMinor, String method, String reference,
                         LocalDate paidOn, String notes) {
    }

    /**
     * A reported payment. Amounts are minor units of {@code currency}.
     *
     * @param targetDescription what the penalty or loan is, e.g. "Late contribution for 2026-10"; null for a contribution
     * @param reviewNote        the approver's note, or why it was rejected
     */
    record SubmissionView(UUID id,
                          UUID memberUserId,
                          String memberName,
                          Integer memberNumber,
                          PaymentSubmission.Kind kind,
                          UUID targetId,
                          String targetDescription,
                          long amountMinor,
                          String currency,
                          String method,
                          String reference,
                          LocalDate paidOn,
                          String notes,
                          PaymentSubmission.Status status,
                          Instant submittedAt,
                          UUID reviewedBy,
                          String reviewerName,
                          Instant reviewedAt,
                          String reviewNote,
                          Instant cancelledAt) {
    }

    /** The kinds of payment the caller may review, for a client to label its queue. */
    List<PaymentSubmission.Kind> reviewableKinds(UUID chamaId, UUID officialUserId);

    /**
     * Pending reports this official can act on, for a nav badge: only the kinds they may review,
     * and not their own reports (someone else must review those).
     */
    ReviewCounts reviewCounts(UUID chamaId, UUID officialUserId);

    /** Per kind; a kind the official may not review is 0. {@code total} is their sum. */
    record ReviewCounts(long contributions, long penaltyPayments, long loanRepayments, long total) {

        public ReviewCounts(long contributions, long penaltyPayments, long loanRepayments) {
            this(contributions, penaltyPayments, loanRepayments, contributions + penaltyPayments + loanRepayments);
        }
    }
}
