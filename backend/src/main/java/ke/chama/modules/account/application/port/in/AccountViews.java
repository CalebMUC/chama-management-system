package ke.chama.modules.account.application.port.in;

import ke.chama.modules.account.domain.model.MemberAccount;
import ke.chama.modules.account.domain.model.SavingsProductKind;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Read models. Amounts are minor units (cents) of {@code currency}. */
public final class AccountViews {

    private AccountViews() {
    }

    /**
     * @param members  members with an open account in the product
     * @param balanceMinor the product's total across every member (its ledger account's balance)
     */
    public record ProductView(UUID id,
                              String code,
                              String name,
                              SavingsProductKind kind,
                              String ledgerAccountCode,
                              String currency,
                              long contributionMinor,
                              int allocationPriority,
                              boolean mandatory,
                              boolean countsTowardLoanLimit,
                              boolean refundableOnExit,
                              boolean earnsDividend,
                              boolean defaultProduct,
                              boolean active,
                              int members,
                              long balanceMinor,
                              Instant createdAt,
                              Instant updatedAt) {
    }

    public record AccountView(UUID id,
                              String accountNumber,
                              UUID memberUserId,
                              String memberName,
                              Integer memberNumber,
                              UUID productId,
                              String productCode,
                              String productName,
                              SavingsProductKind productKind,
                              MemberAccount.Status status,
                              LocalDate openedOn,
                              LocalDate closedOn,
                              String closureReason,
                              String currency,
                              long balanceMinor) {
    }

    /** The caller's own accounts and their combined balance. */
    public record MyAccountsView(String currency, long totalBalanceMinor, List<AccountView> accounts) {
    }

    public record StatementLineView(UUID entryId,
                                    long entryNumber,
                                    LocalDate entryDate,
                                    String description,
                                    String reference,
                                    String sourceType,
                                    long debitMinor,
                                    long creditMinor,
                                    long balanceMinor,
                                    String memo) {
    }

    /** One account's movements, oldest first, from its product's ledger account. */
    public record StatementView(AccountView account,
                                LocalDate from,
                                LocalDate to,
                                long openingBalanceMinor,
                                long closingBalanceMinor,
                                List<StatementLineView> lines) {
    }
}
