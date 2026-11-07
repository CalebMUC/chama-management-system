package ke.chama.modules.investment.presentation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import ke.chama.modules.investment.application.port.in.InvestmentUseCases.Movement;
import ke.chama.modules.investment.application.port.in.InvestmentUseCases.Proposal;
import ke.chama.modules.investment.domain.model.Investment;

import java.time.LocalDate;

public final class InvestmentRequests {

    private InvestmentRequests() {
    }

    /**
     * @param expectedReturnBasisPoints expected yearly return (1250 = 12.5%); optional
     * @param maturityDate              for fixed-term investments; optional, in the future
     */
    public record ProposeRequest(@NotBlank @Size(max = 120) String name,
                                 @NotNull Investment.Type type,
                                 @Size(max = 120) String institution,
                                 @Size(max = 300) String description,
                                 @NotNull @Positive Long amountMinor,
                                 @PositiveOrZero Integer expectedReturnBasisPoints,
                                 LocalDate maturityDate) {

        public Proposal toCommand() {
            return new Proposal(name, type, institution, description, amountMinor, expectedReturnBasisPoints, maturityDate);
        }
    }

    public record ApproveRequest(@Size(max = 300) String note) {
    }

    public record RejectRequest(@NotBlank @Size(max = 300) String reason) {
    }

    /** Funds the approved amount from cash, bank or M-Pesa. */
    public record FundRequest(@NotNull @Pattern(regexp = "CASH|BANK|MPESA", message = "must be CASH, BANK or MPESA")
                              String method,
                              @Size(max = 100) String reference,
                              LocalDate fundedOn) {

        public Movement toCommand() {
            return new Movement(null, method, reference, fundedOn, null);
        }
    }

    /** More money put into an active investment from cash, bank or M-Pesa, e.g. a monthly top-up. */
    public record DepositRequest(@NotNull @Positive Long amountMinor,
                                 @NotNull @Pattern(regexp = "CASH|BANK|MPESA", message = "must be CASH, BANK or MPESA")
                                 String method,
                                 @Size(max = 100) String reference,
                                 LocalDate depositedOn,
                                 @Size(max = 300) String note) {

        public Movement toCommand() {
            return new Movement(amountMinor, method, reference, depositedOn, note);
        }
    }

    /** Income paid into cash, bank or M-Pesa, or REINVESTED in the investment. */
    public record IncomeRequest(@NotNull @Positive Long amountMinor,
                                @NotNull @Pattern(regexp = "CASH|BANK|MPESA|REINVESTED",
                                        message = "must be CASH, BANK, MPESA or REINVESTED") String method,
                                @Size(max = 100) String reference,
                                LocalDate receivedOn,
                                @Size(max = 300) String note) {

        public Movement toCommand() {
            return new Movement(amountMinor, method, reference, receivedOn, note);
        }
    }

    /** Part of the money taken out into cash, bank or M-Pesa. */
    public record WithdrawalRequest(@NotNull @Positive Long amountMinor,
                                    @NotNull @Pattern(regexp = "CASH|BANK|MPESA", message = "must be CASH, BANK or MPESA")
                                    String method,
                                    @Size(max = 100) String reference,
                                    LocalDate withdrawnOn,
                                    @Size(max = 300) String note) {

        public Movement toCommand() {
            return new Movement(amountMinor, method, reference, withdrawnOn, note);
        }
    }

    /** Sold or redeemed: everything that came back. */
    public record CloseRequest(@NotNull @PositiveOrZero Long proceedsMinor,
                               @NotNull @Pattern(regexp = "CASH|BANK|MPESA", message = "must be CASH, BANK or MPESA")
                               String method,
                               @Size(max = 100) String reference,
                               LocalDate closedOn,
                               @Size(max = 300) String note) {

        public Movement toCommand() {
            return new Movement(proceedsMinor, method, reference, closedOn, note);
        }
    }

    public record ValuationRequest(@NotNull @PositiveOrZero Long valueMinor,
                                   LocalDate valuedOn,
                                   @Size(max = 300) String note) {
    }
}
