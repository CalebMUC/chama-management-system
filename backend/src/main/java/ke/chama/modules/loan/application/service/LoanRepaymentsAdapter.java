package ke.chama.modules.loan.application.service;

import ke.chama.modules.loan.api.LoanRepayments;
import ke.chama.modules.loan.application.port.in.LoanUseCases;
import ke.chama.modules.loan.application.port.in.LoanUseCases.Repayment;
import ke.chama.modules.loan.application.port.out.LoanRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** {@link LoanRepayments} over the same use case officials call. */
@Service
@Transactional
class LoanRepaymentsAdapter implements LoanRepayments {

    private final LoanUseCases loans;
    private final LoanRepository repository;

    LoanRepaymentsAdapter(LoanUseCases loans, LoanRepository repository) {
        this.loans = loans;
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RepayableLoan> loan(UUID chamaId, UUID loanId) {
        return repository.loan(chamaId, loanId).map(l -> new RepayableLoan(l.id(), l.borrowerUserId(), l.purpose(),
                l.status().name(), l.outstandingMinor(), l.currency()));
    }

    @Override
    public void recordRepayment(UUID chamaId, UUID officialUserId, UUID loanId, long amountMinor, String method,
                                String reference, LocalDate paidOn) {
        loans.recordRepayment(chamaId, officialUserId, loanId, new Repayment(amountMinor, method, reference, paidOn));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean referenceUsed(UUID chamaId, String method, String reference) {
        return reference != null && repository.referenceExists(chamaId, method, reference.trim().toUpperCase(Locale.ROOT));
    }
}
