package ke.chama.modules.memberexit.presentation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import ke.chama.modules.memberexit.application.port.in.ExitUseCases.Payout;

import java.time.LocalDate;
import java.util.UUID;

public final class ExitRequests {

    private ExitRequests() {
    }

    /** E.g. {@code {"memberUserId":"...","reason":"Relocating to Mombasa"}}. */
    public record StartExitRequest(@NotNull UUID memberUserId, @NotBlank @Size(max = 300) String reason) {
    }

    public record ApproveRequest(@Size(max = 300) String note) {
    }

    public record RejectRequest(@NotBlank @Size(max = 300) String reason) {
    }

    /**
     * Needed only when something is paid out to the member.
     *
     * @param method    CASH, BANK or MPESA
     * @param reference required for MPESA (transaction code) and BANK (slip or transfer number)
     * @param settledOn defaults to today; cannot be in the future or before the exit was started
     */
    public record SettleRequest(@Pattern(regexp = "CASH|BANK|MPESA", message = "must be CASH, BANK or MPESA") String method,
                                @Size(max = 100) String reference,
                                LocalDate settledOn) {

        public Payout toCommand() {
            return new Payout(method, reference, settledOn);
        }
    }
}
