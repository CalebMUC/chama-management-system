package ke.chama.modules.submission.api;

import java.util.UUID;

/** Reported payments waiting for an official, for dashboards and work queues. */
public interface PaymentSubmissionQueue {

    /** Pending reports in the chama, per kind. */
    PendingCounts pending(UUID chamaId);

    /** The member's own reports still waiting for review. */
    long pendingOf(UUID chamaId, UUID memberUserId);

    record PendingCounts(long contributions, long penaltyPayments, long loanRepayments) {

        public long total() {
            return contributions + penaltyPayments + loanRepayments;
        }
    }
}
