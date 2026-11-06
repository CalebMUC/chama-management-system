package ke.chama.modules.contribution.presentation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import ke.chama.modules.contribution.application.port.in.ContributionUseCases.RecordPayment;
import ke.chama.modules.contribution.domain.model.PaymentMethod;

import java.time.LocalDate;
import java.util.UUID;

public final class ContributionRequests {

    private ContributionRequests() {
    }

    /**
     * Money received from a member, e.g.
     * {@code {"memberUserId":"...","amountMinor":500000,"method":"MPESA","reference":"QK7H2XY9ZB"}}.
     *
     * @param reference required for MPESA (transaction code) and BANK (slip number)
     * @param paidOn    defaults to today; cannot be in the future
     */
    public record RecordPaymentRequest(@NotNull UUID memberUserId,
                                       @NotNull @Positive Long amountMinor,
                                       @NotNull PaymentMethod method,
                                       @Size(max = 100) String reference,
                                       LocalDate paidOn,
                                       @Size(max = 300) String notes) {

        public RecordPayment toCommand() {
            return new RecordPayment(memberUserId, amountMinor, method, reference, paidOn, notes);
        }
    }

    public record ReversePaymentRequest(@NotBlank @Size(max = 300) String reason) {
    }
}
