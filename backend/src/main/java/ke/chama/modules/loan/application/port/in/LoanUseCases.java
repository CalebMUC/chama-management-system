package ke.chama.modules.loan.application.port.in;

import ke.chama.modules.loan.domain.model.Loan;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Loans of the caller's chama: apply, guarantee, approve by quorum, disburse and repay. Money
 * movements post to the ledger against the borrower's "Loans receivable"; every step is audited.
 */
public interface LoanUseCases {

    EligibilityView eligibility(UUID chamaId, UUID memberUserId);

    LoanDetailView apply(UUID chamaId, UUID borrowerUserId, Application application);

    LoanDetailView addGuarantor(UUID chamaId, UUID actorUserId, UUID loanId, UUID guarantorUserId);

    LoanDetailView respondAsGuarantor(UUID chamaId, UUID guarantorUserId, UUID loanId, boolean accept);

    LoanDetailView cancel(UUID chamaId, UUID actorUserId, UUID loanId);

    LoanDetailView approve(UUID chamaId, UUID actorUserId, UUID loanId, String note);

    LoanDetailView reject(UUID chamaId, UUID actorUserId, UUID loanId, String reason);

    LoanDetailView disburse(UUID chamaId, UUID actorUserId, UUID loanId, Transaction disbursement);

    LoanDetailView recordRepayment(UUID chamaId, UUID actorUserId, UUID loanId, Repayment repayment);

    LoanDetailView loan(UUID chamaId, UUID loanId);

    Page<LoanView> loans(UUID chamaId, UUID borrowerUserId, Loan.Status status, Pageable pageable);

    MyLoansView mine(UUID chamaId, UUID memberUserId);

    List<GuaranteeView> guaranteeRequests(UUID chamaId, UUID memberUserId);

    record Application(long principalMinor, int termMonths, String purpose, List<UUID> guarantorUserIds) {
    }

    /** A disbursement: how the money was paid out. */
    record Transaction(String method, String reference, LocalDate date) {
    }

    record Repayment(long amountMinor, String method, String reference, LocalDate paidOn) {
    }

    /**
     * What the member may borrow now. Amounts are minor units of {@code currency}.
     *
     * @param reasons why the member cannot borrow; empty when {@code eligible}
     */
    record EligibilityView(boolean eligible,
                           List<String> reasons,
                           String currency,
                           long savingsMinor,
                           long maxLoanMinor,
                           int maxMultiplier,
                           int interestBasisPoints,
                           int maxTermMonths,
                           int membershipMonths,
                           int minMembershipMonths,
                           long guarantorThresholdMinor,
                           int guarantorsRequired,
                           int approvalQuorum) {
    }

    /**
     * Amounts are minor units of {@code currency}. {@code outstandingMinor} and {@code arrearsMinor}
     * are zero until the loan is disbursed.
     *
     * @param arrearsMinor instalments past their due date and still unpaid
     */
    record LoanView(UUID id,
                    UUID borrowerUserId,
                    String borrowerName,
                    Integer memberNumber,
                    long principalMinor,
                    int interestBasisPoints,
                    long interestMinor,
                    long totalDueMinor,
                    long repaidMinor,
                    long outstandingMinor,
                    long arrearsMinor,
                    String currency,
                    int termMonths,
                    String purpose,
                    Loan.Status status,
                    LocalDate appliedOn,
                    int requiredGuarantors,
                    long acceptedGuarantors,
                    int requiredApprovals,
                    int approvals,
                    LocalDate disbursedOn,
                    LocalDate nextDueDate,
                    long nextDueMinor,
                    LocalDate maturityDate,
                    String rejectionReason) {
    }

    record GuarantorView(UUID userId, String name, Integer memberNumber, Loan.Guarantor.Status status,
                         Instant requestedAt, Instant respondedAt) {
    }

    record ApprovalView(UUID approverUserId, String name, String note, Instant approvedAt) {
    }

    /** @param status PAID, PARTLY_PAID, DUE or OVERDUE */
    record InstalmentView(int number, LocalDate dueDate, long amountMinor, long paidMinor, String status) {
    }

    record RepaymentView(UUID id, long amountMinor, String method, String reference, LocalDate paidOn,
                         UUID ledgerEntryId, UUID recordedBy, Instant recordedAt) {
    }

    record DisbursementView(LocalDate disbursedOn, UUID disbursedBy, String method, String reference,
                            UUID ledgerEntryId) {
    }

    record DecisionView(UUID rejectedBy, Instant rejectedAt, String reason, Instant cancelledAt) {
    }

    record LoanDetailView(LoanView loan,
                          List<GuarantorView> guarantors,
                          List<ApprovalView> approvals,
                          DisbursementView disbursement,
                          DecisionView decision,
                          List<InstalmentView> schedule,
                          List<RepaymentView> repayments) {
    }

    /** The member's own loans, newest first. */
    record MyLoansView(String currency, long outstandingMinor, long arrearsMinor, List<LoanDetailView> loans) {
    }

    /** A loan the member was asked to guarantee, and their answer. */
    record GuaranteeView(LoanView loan, Loan.Guarantor.Status myStatus) {
    }
}
