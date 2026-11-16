package ke.chama.modules.loan.domain.model;

import ke.chama.modules.loan.domain.exception.LoanExceptions.InvalidLoanException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A repayment towards one loan, posted to the ledger.
 *
 * @param method CASH, BANK or MPESA; M-Pesa and bank repayments carry their reference. SAVINGS when
 *               it was taken from the borrower's own savings as they left the chama.
 */
public record LoanRepayment(UUID id,
                            UUID chamaId,
                            UUID loanId,
                            UUID borrowerUserId,
                            long amountMinor,
                            String currency,
                            String method,
                            String reference,
                            LocalDate paidOn,
                            UUID ledgerEntryId,
                            UUID recordedBy,
                            Instant recordedAt) {

    public static final String SAVINGS = "SAVINGS";

    public static LoanRepayment record(Loan loan, long amountMinor, String method, String reference, LocalDate paidOn,
                                       UUID recordedBy, LocalDate today, Instant now) {
        String ref = Loan.validPayment(method, reference, paidOn, today);
        LocalDate date = paidOn == null ? today : paidOn;
        if (loan.disbursedOn() != null && date.isBefore(loan.disbursedOn())) {
            throw new InvalidLoanException("A repayment cannot be dated before the loan was disbursed");
        }
        return new LoanRepayment(UUID.randomUUID(), loan.chamaId(), loan.id(), loan.borrowerUserId(), amountMinor,
                loan.currency(), method, ref, date, null, recordedBy, now);
    }

    /** Taken from the borrower's savings when they leave the chama; no external reference. */
    public static LoanRepayment fromSavings(Loan loan, long amountMinor, LocalDate paidOn, UUID recordedBy,
                                            LocalDate today, Instant now) {
        if (paidOn.isAfter(today)) {
            throw new InvalidLoanException("A transaction cannot be dated in the future");
        }
        if (loan.disbursedOn() != null && paidOn.isBefore(loan.disbursedOn())) {
            throw new InvalidLoanException("A repayment cannot be dated before the loan was disbursed");
        }
        return new LoanRepayment(UUID.randomUUID(), loan.chamaId(), loan.id(), loan.borrowerUserId(), amountMinor,
                loan.currency(), SAVINGS, null, paidOn, null, recordedBy, now);
    }

    public LoanRepayment postedAs(UUID entryId) {
        return new LoanRepayment(id, chamaId, loanId, borrowerUserId, amountMinor, currency, method, reference, paidOn,
                entryId, recordedBy, recordedAt);
    }
}
