package ke.chama.modules.loan.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The chama's loans as they stand today, for reports and dashboards. */
public interface LoanPortfolio {

    /** Every loan, newest first. */
    List<LoanSnapshot> loans(UUID chamaId);

    /** One member's loans, newest first. */
    List<LoanSnapshot> loansOf(UUID chamaId, UUID memberUserId);

    /** Guarantee requests the member has not answered yet. */
    int pendingGuaranteeRequests(UUID chamaId, UUID memberUserId);

    /**
     * One loan today. Amounts are minor units of {@code currency}; owed amounts are zero until it is disbursed.
     *
     * @param status          PENDING_GUARANTORS, PENDING_APPROVAL, APPROVED, ACTIVE, REPAID, REJECTED or CANCELLED
     * @param arrearsMinor    instalments past their due date and still unpaid
     * @param daysInArrears   days since the oldest unpaid instalment fell due; 0 when none is overdue
     */
    record LoanSnapshot(UUID id,
                        UUID borrowerUserId,
                        UUID productId,
                        String productCode,
                        String productName,
                        String status,
                        String currency,
                        long principalMinor,
                        long interestMinor,
                        long totalDueMinor,
                        long repaidMinor,
                        long outstandingMinor,
                        long arrearsMinor,
                        int daysInArrears,
                        int termMonths,
                        String purpose,
                        LocalDate appliedOn,
                        LocalDate disbursedOn,
                        LocalDate maturityDate,
                        LocalDate nextDueDate,
                        long nextDueMinor) {

        public boolean active() {
            return "ACTIVE".equals(status);
        }

        /** Applied for and not yet decided or disbursed. */
        public boolean inProgress() {
            return "PENDING_GUARANTORS".equals(status) || "PENDING_APPROVAL".equals(status) || "APPROVED".equals(status);
        }
    }
}
