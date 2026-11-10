package ke.chama.modules.loan.api;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Loan repayments for another module, recorded exactly as an official recording one by hand
 * would (not on your own loan, active loans only, at most what is outstanding, unique reference,
 * posted to the ledger, audit-logged). Runs in the caller's transaction.
 */
public interface LoanRepayments {

    /** The loan as it stands, if it exists in the chama. */
    Optional<RepayableLoan> loan(UUID chamaId, UUID loanId);

    /** @param method CASH, BANK or MPESA */
    void recordRepayment(UUID chamaId, UUID officialUserId, UUID loanId, long amountMinor, String method,
                         String reference, LocalDate paidOn);

    /** A loan repayment or disbursement already carries this reference. */
    boolean referenceUsed(UUID chamaId, String method, String reference);

    /**
     * @param status PENDING_GUARANTORS, PENDING_APPROVAL, APPROVED, ACTIVE, REPAID, REJECTED or CANCELLED
     */
    record RepayableLoan(UUID id, UUID borrowerUserId, String purpose, String status, long outstandingMinor,
                         String currency) {

        public boolean active() {
            return "ACTIVE".equals(status);
        }
    }
}
