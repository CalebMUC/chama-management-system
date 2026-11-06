package ke.chama.modules.transaction.presentation.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import ke.chama.modules.transaction.application.port.in.LedgerUseCases.ManualEntry;
import ke.chama.modules.transaction.application.port.in.LedgerUseCases.ManualLine;
import ke.chama.modules.transaction.domain.model.AccountType;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class LedgerRequests {

    private LedgerRequests() {
    }

    public record CreateAccountRequest(@NotBlank @Size(max = 10) String code,
                                       @NotBlank @Size(max = 100) String name,
                                       @NotNull AccountType type) {
    }

    public record AccountStatusRequest(@NotNull Boolean active) {
    }

    /**
     * A manual journal entry, e.g. the opening balances when a chama moves its books here:
     * <pre>{@code
     * {"description": "Opening balances", "entryDate": "2026-11-01",
     *  "lines": [{"accountCode": "1010", "debitMinor": 12000000},
     *            {"accountCode": "2000", "creditMinor": 12000000, "memberUserId": "..."}]}
     * }</pre>
     *
     * @param entryDate defaults to today; cannot be in the future
     * @param reference e.g. a bank slip or M-Pesa code
     */
    public record ManualEntryRequest(LocalDate entryDate,
                                     @NotBlank @Size(max = 500) String description,
                                     @Size(max = 100) String reference,
                                     @NotEmpty @Size(min = 2, max = 200) List<@Valid @NotNull LineRequest> lines) {

        public ManualEntry toEntry() {
            return new ManualEntry(entryDate, description, reference,
                    lines.stream().map(LineRequest::toLine).toList());
        }
    }

    /**
     * Give either {@code debitMinor} or {@code creditMinor}.
     *
     * @param memberUserId required on member accounts (2000 Member savings, 1100 Loans receivable)
     */
    public record LineRequest(@NotBlank @Size(max = 10) String accountCode,
                              @PositiveOrZero Long debitMinor,
                              @PositiveOrZero Long creditMinor,
                              UUID memberUserId,
                              @Size(max = 200) String memo) {

        ManualLine toLine() {
            return new ManualLine(accountCode, debitMinor == null ? 0 : debitMinor,
                    creditMinor == null ? 0 : creditMinor, memberUserId, memo);
        }
    }

    public record ReverseEntryRequest(@NotBlank @Size(max = 300) String reason) {
    }
}
