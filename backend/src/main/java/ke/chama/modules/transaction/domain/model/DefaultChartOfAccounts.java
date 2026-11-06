package ke.chama.modules.transaction.domain.model;

import ke.chama.modules.transaction.api.LedgerAccountKey;

import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import static ke.chama.modules.transaction.api.LedgerAccountKey.*;
import static ke.chama.modules.transaction.domain.model.AccountType.*;

/**
 * The accounts every chama starts with. Codes follow the usual numbering: 1xxx assets,
 * 2xxx liabilities, 3xxx equity, 4xxx income, 5xxx expenses.
 * <p>
 * Migration V10 backfilled this same chart for chamas created before the ledger existed.
 */
public final class DefaultChartOfAccounts {

    private record Template(String code, String name, AccountType type, LedgerAccountKey key, boolean memberLedger) {
    }

    private static final List<Template> TEMPLATES = List.of(
            new Template("1000", "Cash on hand", ASSET, CASH, false),
            new Template("1010", "Bank account", ASSET, BANK, false),
            new Template("1020", "Mobile money (M-Pesa)", ASSET, MOBILE_MONEY, false),
            new Template("1100", "Loans receivable", ASSET, LOANS_RECEIVABLE, true),
            new Template("1200", "Investments", ASSET, INVESTMENTS, false),
            new Template("2000", "Member savings", LIABILITY, MEMBER_SAVINGS, true),
            new Template("3000", "Retained surplus", EQUITY, RETAINED_SURPLUS, false),
            new Template("4000", "Loan interest income", INCOME, INTEREST_INCOME, false),
            new Template("4100", "Penalty income", INCOME, PENALTY_INCOME, false),
            new Template("4200", "Investment income", INCOME, INVESTMENT_INCOME, false),
            new Template("4900", "Other income", INCOME, OTHER_INCOME, false),
            new Template("5000", "Operating expenses", EXPENSE, OPERATING_EXPENSES, false),
            new Template("5100", "Bank and transaction charges", EXPENSE, BANK_CHARGES, false));

    private DefaultChartOfAccounts() {
    }

    public static List<LedgerAccount> forChama(UUID chamaId, Currency currency, Instant now) {
        return TEMPLATES.stream()
                .map(t -> LedgerAccount.system(chamaId, t.code(), t.name(), t.type(), t.key(), t.memberLedger(),
                        currency, now))
                .toList();
    }
}
