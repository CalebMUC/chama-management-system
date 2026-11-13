package ke.chama.modules.loan.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Bringing over the loans of a chama that ran for years before it used the system: each with its
 * disbursement and every repayment, posted to the ledger on their own days. Loans still being repaid
 * carry on in the system with their schedule (equal monthly instalments of principal plus flat
 * interest, the first a month after disbursement). Writes run in the caller's transaction; nobody
 * is emailed.
 */
public interface LoanHistory {

    /** A loan product, for the template and checks. */
    record Product(UUID id, String code, String name, int interestBasisPoints, int maxTermMonths, boolean defaultProduct,
                   boolean active) {
    }

    List<Product> products(UUID chamaId);

    /** References used by disbursements and repayments, as METHOD:REFERENCE upper case. */
    Set<String> usedReferences(UUID chamaId);

    /** Loans in progress (pending, approved or active) now: borrower to the products they are in. */
    Map<UUID, Set<UUID>> openLoans(UUID chamaId);

    /** @param method CASH, BANK or MPESA */
    record HistoricalRepayment(LocalDate paidOn, long amountMinor, String method, String reference) {
    }

    /**
     * @param productId     the loan product; null for the default one
     * @param interestMinor the flat interest for the whole term
     * @param method        how it was paid out: CASH, BANK or MPESA
     * @param repayments    oldest first; at most principal plus interest in all
     */
    record HistoricalLoan(UUID borrowerUserId, UUID productId, long principalMinor, long interestMinor, int termMonths,
                          LocalDate disbursedOn, String purpose, String method, String reference,
                          List<HistoricalRepayment> repayments) {

        public HistoricalLoan {
            repayments = List.copyOf(repayments);
        }

        public long repaidMinor() {
            return repayments.stream().mapToLong(HistoricalRepayment::amountMinor).sum();
        }
    }

    /**
     * Where the loan stands today, worked out as the system would without saving anything.
     *
     * @param status         ACTIVE or REPAID
     * @param arrearsMinor   instalments past their due date and still unpaid
     * @param nextDueDate    the first instalment not fully paid; null when repaid
     * @param nextDueMinor   what is still unpaid of it
     */
    record Projection(String status, long totalDueMinor, long repaidMinor, long outstandingMinor, long arrearsMinor,
                      int instalmentsOverdue, LocalDate nextDueDate, long nextDueMinor, LocalDate maturityDate) {
    }

    Projection project(UUID chamaId, HistoricalLoan loan);

    /** Records the loans and their repayments, tagged with the batch. Returns the loans' ids, in order. */
    List<UUID> importLoans(UUID chamaId, UUID actorUserId, UUID batchId, List<HistoricalLoan> loans);

    /** Why the batch's loans cannot be removed now, e.g. a repayment was recorded on one since. */
    Optional<String> undoBlocked(UUID chamaId, UUID batchId);

    /** Removes the batch's loans and repayments, reversing their entries on their own days. Returns how many loans. */
    int undoLoans(UUID chamaId, UUID actorUserId, UUID batchId);
}
