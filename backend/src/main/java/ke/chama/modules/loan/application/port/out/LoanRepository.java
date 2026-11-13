package ke.chama.modules.loan.application.port.out;

import ke.chama.modules.loan.domain.model.Loan;
import ke.chama.modules.loan.domain.model.LoanRepayment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Row-level security limits every query to the current chama. Loans load with their guarantors and approvals. */
public interface LoanRepository {

    void insert(Loan loan);

    /** Saves the loan, its guarantors and approvals; false if the loan changed since it was read. */
    boolean update(Loan loan);

    Optional<Loan> loan(UUID chamaId, UUID loanId);

    /** Every filter is optional. Newest first. */
    Page<Loan> loans(UUID chamaId, UUID borrowerUserId, Loan.Status status, Pageable pageable);

    List<Loan> loansOf(UUID chamaId, UUID borrowerUserId);

    /** Every loan of the chama, newest first. */
    List<Loan> allLoans(UUID chamaId);

    /** Loans the member was asked to guarantee, newest first. */
    List<Loan> guaranteedBy(UUID chamaId, UUID guarantorUserId);

    /** Whether the member has a loan in progress of this product. */
    boolean hasOpenLoan(UUID chamaId, UUID borrowerUserId, UUID productId);

    /** Members with a loan in progress (pending, approved or active). */
    Set<UUID> borrowersWithOpenLoans(UUID chamaId);

    void insertRepayment(LoanRepayment repayment);

    List<LoanRepayment> repayments(UUID chamaId, UUID loanId);

    /** Whether a disbursement or repayment already used this M-Pesa code or bank slip. */
    boolean referenceExists(UUID chamaId, String method, String reference);

    // ---- history brought over from spreadsheets -------------------------------------------------

    /** Tags loans and repayments as created by an import batch. */
    void markImported(UUID chamaId, java.util.Collection<UUID> loanIds, java.util.Collection<UUID> repaymentIds, UUID batchId);

    List<Loan> importedLoans(UUID chamaId, UUID batchId);

    /** Repayments on the batch's loans that the batch did not create (recorded by hand since). */
    long laterRepayments(UUID chamaId, UUID batchId);

    /** Deletes the batch's loans and their repayments. */
    void deleteImported(UUID chamaId, UUID batchId);

    /** References of disbursements and repayments, as METHOD:REFERENCE upper case. */
    Set<String> usedReferences(UUID chamaId);
}
