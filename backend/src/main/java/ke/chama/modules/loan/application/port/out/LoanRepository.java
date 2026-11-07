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
}
