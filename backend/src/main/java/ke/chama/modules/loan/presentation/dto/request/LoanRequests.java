package ke.chama.modules.loan.presentation.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import ke.chama.modules.loan.application.port.in.LoanUseCases.Application;
import ke.chama.modules.loan.application.port.in.LoanUseCases.Repayment;
import ke.chama.modules.loan.application.port.in.LoanUseCases.Transaction;
import ke.chama.modules.loan.domain.model.Loan;
import ke.chama.modules.loan.domain.model.LoanProduct;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class LoanRequests {

    private LoanRequests() {
    }

    /**
     * @param productId        the loan product (see GET /loan-products); the default product when left out
     * @param guarantorUserIds active members asked to guarantee the loan; needed only above the
     *                         product's guarantor threshold (see GET /loans/eligibility)
     */
    public record ApplyRequest(UUID productId,
                               @NotNull @Positive Long principalMinor,
                               @NotNull @Min(1) @Max(Loan.MAX_TERM_MONTHS) Integer termMonths,
                               @NotBlank @Size(max = 300) String purpose,
                               @Size(max = 10) List<@NotNull UUID> guarantorUserIds) {

        public Application toCommand() {
            return new Application(productId, principalMinor, termMonths, purpose,
                    guarantorUserIds == null ? List.of() : guarantorUserIds);
        }
    }

    /**
     * A loan product, e.g. {@code {"code":"EMERG","name":"Emergency loan","interestBasisPoints":500,
     * "maxTermMonths":3,"maxMultiplier":1,"minMembershipMonths":0,"guarantorThresholdMinor":0,"guarantorsRequired":0}}.
     * {@code code} is ignored on updates.
     */
    public record LoanProductRequest(@Size(max = 10) String code,
                                     @NotBlank @Size(max = 60) String name,
                                     @NotNull @Min(0) @Max(10_000) Integer interestBasisPoints,
                                     @NotNull @Min(1) @Max(Loan.MAX_TERM_MONTHS) Integer maxTermMonths,
                                     @NotNull @Min(1) @Max(10) Integer maxMultiplier,
                                     @NotNull @Min(0) @Max(60) Integer minMembershipMonths,
                                     @NotNull @PositiveOrZero Long guarantorThresholdMinor,
                                     @NotNull @Min(0) @Max(5) Integer guarantorsRequired) {

        public LoanProduct.Terms terms() {
            return new LoanProduct.Terms(name, interestBasisPoints, maxTermMonths, maxMultiplier, minMembershipMonths,
                    guarantorThresholdMinor, guarantorsRequired);
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
