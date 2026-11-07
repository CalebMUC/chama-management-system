package ke.chama.modules.penalty.presentation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import ke.chama.modules.penalty.application.port.in.PenaltyUseCases.PaymentRequest;

import java.time.LocalDate;
import java.util.UUID;

public final class PenaltyRequests {

    private PenaltyRequests() {
    }

    /** A fine for something other than a late contribution, e.g. missing a meeting. */
    public record FineRequest(@NotNull UUID memberUserId,
                              @NotNull @Positive Long amountMinor,
                              @NotBlank @Size(max = 300) String reason) {
    }

    /**
     * @param reference required for MPESA (transaction code) and BANK (slip number)
     * @param paidOn    defaults to today; cannot be in the future
     */
    public record PenaltyPaymentRequest(@NotNull @Positive Long amountMinor,
                                        @NotNull @Pattern(regexp = "CASH|BANK|MPESA", message = "must be CASH, BANK or MPESA")
                                        String method,
                                        @Size(max = 100) String reference,
                                        LocalDate paidOn) {

        public PaymentRequest toCommand() {
            return new PaymentRequest(amountMinor, method, reference, paidOn);
        }
    }

    public record WaiveRequest(@NotBlank @Size(max = 300) String reason) {
    }
}
