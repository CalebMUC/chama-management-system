package ke.chama.modules.expense.presentation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import ke.chama.modules.expense.application.port.in.ExpenseUseCases.NewExpense;
import ke.chama.modules.expense.application.port.in.ExpenseUseCases.Payment;

import java.time.LocalDate;
import java.util.UUID;

public final class ExpenseRequests {

    private ExpenseRequests() {
    }

    /**
     * @param accountCode  an active expense account; defaults to 5000 Operating expenses
     * @param investmentId the active investment the expense is for, e.g. the Airbnb's rent; optional
     * @param incurredOn   defaults to today; cannot be in the future
     */
    public record ExpenseRequest(@NotNull @Positive Long amountMinor,
                                 @NotBlank @Size(max = 120) String payee,
                                 @NotBlank @Size(max = 300) String description,
                                 @Size(max = 10) String accountCode,
                                 UUID investmentId,
                                 LocalDate incurredOn) {

        public NewExpense toCommand() {
            return new NewExpense(amountMinor, payee, description, accountCode, investmentId, incurredOn);
        }
    }

    public record ApproveRequest(@Size(max = 300) String note) {
    }

    public record RejectRequest(@NotBlank @Size(max = 300) String reason) {
    }

    /**
     * @param reference required for MPESA (transaction code) and BANK (slip or transfer number)
     * @param paidOn    defaults to today; cannot be in the future
     */
    public record PayRequest(@NotNull @Pattern(regexp = "CASH|BANK|MPESA", message = "must be CASH, BANK or MPESA")
                             String method,
                             @Size(max = 100) String reference,
                             LocalDate paidOn) {

        public Payment toCommand() {
            return new Payment(method, reference, paidOn);
        }
    }
}
