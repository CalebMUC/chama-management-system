package ke.chama.modules.loan.presentation.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import ke.chama.modules.loan.application.port.in.LoanUseCases.Application;
import ke.chama.modules.loan.application.port.in.LoanUseCases.Repayment;
import ke.chama.modules.loan.application.port.in.LoanUseCases.Transaction;
import ke.chama.modules.loan.domain.model.Loan;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class LoanRequests {

    private LoanRequests() {
    }

    /**
     * @param guarantorUserIds active members asked to guarantee the loan; needed only above the
     *                         chama's guarantor threshold (see GET /loans/eligibility)
     */
    public record ApplyRequest(@NotNull @Positive Long principalMinor,
                               @NotNull @Min(1) @Max(Loan.MAX_TERM_MONTHS) Integer termMonths,
                               @NotBlank @Size(max = 300) String purpose,
                               @Size(max = 10) List<@NotNull UUID> guarantorUserIds) {

        public Application toCommand() {
            return new Application(principalMinor, termMonths, purpose, guarantorUserIds == null ? List.of() : guarantorUserIds);
        }
    }

    public record AddGuarantorRequest(@NotNull UUID guarantorUserId) {
    }

    public record GuaranteeResponse(@NotNull Boolean accept) {
    }

    public record ApproveRequest(@Size(max = 300) String note) {
    }

    public record RejectRequest(@NotBlank @Size(max = 300) String reason) {
    }

    /**
     * @param reference     required for MPESA (transaction code) and BANK (slip or transfer number)
     * @param disbursedOn   defaults to today; cannot be in the future
     */
    public record DisburseRequest(@NotNull @Pattern(regexp = "CASH|BANK|MPESA", message = "must be CASH, BANK or MPESA")
                                  String method,
                                  @Size(max = 100) String reference,
                                  LocalDate disbursedOn) {

        public Transaction toCommand() {
            return new Transaction(method, reference, disbursedOn);
        }
    }

    /**
     * @param reference required for MPESA (transaction code) and BANK (slip number)
     * @param paidOn    defaults to today; cannot be in the future
     */
    public record RepaymentRequest(@NotNull @Positive Long amountMinor,
                                   @NotNull @Pattern(regexp = "CASH|BANK|MPESA", message = "must be CASH, BANK or MPESA")
                                   String method,
                                   @Size(max = 100) String reference,
                                   LocalDate paidOn) {

        public Repayment toCommand() {
            return new Repayment(amountMinor, method, reference, paidOn);
        }
    }
}
