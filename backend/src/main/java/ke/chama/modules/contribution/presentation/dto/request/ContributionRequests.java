package ke.chama.modules.contribution.presentation.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import ke.chama.modules.contribution.application.port.in.ContributionUseCases.RecordPayment;
import ke.chama.modules.contribution.domain.model.PaymentAllocation.Allocation;
import ke.chama.modules.contribution.domain.model.PaymentMethod;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ContributionRequests {

    private ContributionRequests() {
    }

    /**
     * Money received from a member, e.g.
     * {@code {"memberUserId":"...","amountMinor":500000,"method":"MPESA","reference":"QK7H2XY9ZB"}}.
     *
     * @param reference   required for MPESA (transaction code) and BANK (slip number)
     * @param paidOn      defaults to today; cannot be in the future
     * @param allocations optional: how to split the payment across savings products, adding up to
     *                    the amount. Left out, it clears the oldest dues first (by product priority
     *                    within a period) and the rest goes to the default product.
     */
    public record RecordPaymentRequest(@NotNull UUID memberUserId,
                                       @NotNull @Positive Long amountMinor,
                                       @NotNull PaymentMethod method,
                                       @Size(max = 100) String reference,
                                       LocalDate paidOn,
                                       @Size(max = 300) String notes,
                                       @Size(min = 1, max = 20) List<@Valid @NotNull AllocationRequest> allocations) {

        public RecordPayment toCommand() {
            return new RecordPayment(memberUserId, amountMinor, method, reference, paidOn, notes,
                    allocations == null ? null
                            : allocations.stream().map(a -> new Allocation(a.productId(), a.amountMinor())).toList());
        }
    }

    public record AllocationRequest(@NotNull UUID productId, @NotNull @Positive Long amountMinor) {
    }

    public record ReversePaymentRequest(@NotBlank @Size(max = 300) String reason) {
    }
}
