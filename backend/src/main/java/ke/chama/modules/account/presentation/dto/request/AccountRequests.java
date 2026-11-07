package ke.chama.modules.account.presentation.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import ke.chama.modules.account.domain.model.SavingsProduct;
import ke.chama.modules.account.domain.model.SavingsProductKind;

import java.util.UUID;

public final class AccountRequests {

    private AccountRequests() {
    }

    /**
     * A new savings product, e.g.
     * {@code {"code":"SHR","name":"Share capital","kind":"SHARE_CAPITAL","contributionMinor":50000,"mandatory":true}}.
     *
     * @param ledgerAccountCode     optional; the next free code for the kind when left out
     * @param allocationPriority    optional, 1-99 (default 10: ahead of Deposits at 50)
     * @param countsTowardLoanLimit optional; the kind's default when left out (likewise the other flags)
     */
    public record CreateProductRequest(@NotBlank @Size(max = 10) String code,
                                       @NotBlank @Size(max = 60) String name,
                                       @NotNull SavingsProductKind kind,
                                       @Size(max = 10) String ledgerAccountCode,
                                       @NotNull @PositiveOrZero Long contributionMinor,
                                       @Min(1) @Max(99) Integer allocationPriority,
                                       @NotNull Boolean mandatory,
                                       Boolean countsTowardLoanLimit,
                                       Boolean refundableOnExit,
                                       Boolean earnsDividend) {

        public SavingsProduct.Terms terms() {
            return new SavingsProduct.Terms(name, contributionMinor,
                    allocationPriority == null ? 10 : allocationPriority, mandatory, countsTowardLoanLimit,
                    refundableOnExit, earnsDividend);
        }
    }

    /** Every term of an existing product; flags left out keep their current value. */
    public record UpdateProductRequest(@NotBlank @Size(max = 60) String name,
                                       @NotNull @PositiveOrZero Long contributionMinor,
                                       @NotNull @Min(1) @Max(99) Integer allocationPriority,
                                       @NotNull Boolean mandatory,
                                       Boolean countsTowardLoanLimit,
                                       Boolean refundableOnExit,
                                       Boolean earnsDividend) {

        public SavingsProduct.Terms terms() {
            return new SavingsProduct.Terms(name, contributionMinor, allocationPriority, mandatory,
                    countsTowardLoanLimit, refundableOnExit, earnsDividend);
        }
    }

    public record OpenAccountRequest(@NotNull UUID memberUserId, @NotNull UUID productId) {
    }

    public record CloseAccountRequest(@NotBlank @Size(max = 300) String reason) {
    }
}
