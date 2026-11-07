package ke.chama.modules.report.domain.model;

import ke.chama.modules.report.domain.model.FinancialStatements.AccountBalance;
import ke.chama.modules.report.domain.model.FinancialStatements.Line;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FinancialStatementsTest {

    // KES 50,000 saved via M-Pesa, KES 30,000 lent out with KES 3,000 interest, a KES 500 fine, KES 1,000 spent.
    private static final List<AccountBalance> BOOKS = List.of(
            new AccountBalance("1000", "Cash on hand", "ASSET", 0),
            new AccountBalance("1020", "Mobile money (M-Pesa)", "ASSET", 1_900_000),
            new AccountBalance("1100", "Loans receivable", "ASSET", 3_300_000),
            new AccountBalance("1110", "Penalties receivable", "ASSET", 50_000),
            new AccountBalance("2000", "Member savings", "LIABILITY", 5_000_000),
            new AccountBalance("3000", "Retained surplus", "EQUITY", 0),
            new AccountBalance("4000", "Loan interest income", "INCOME", 300_000),
            new AccountBalance("4100", "Penalty income", "INCOME", 50_000),
            new AccountBalance("5000", "Operating expenses", "EXPENSE", 100_000));

    @Test
    void assetsEqualLiabilitiesPlusEquityWithTheSurplusToDate() {
        FinancialStatements.Position p = FinancialStatements.position(BOOKS);

        assertThat(p.assets().totalMinor()).isEqualTo(5_250_000);
        assertThat(p.assets().lines()).extracting(Line::code).containsExactly("1020", "1100", "1110");
        assertThat(p.liabilities().totalMinor()).isEqualTo(5_000_000);
        assertThat(p.equity().lines()).containsExactly(new Line(null, FinancialStatements.SURPLUS_TO_DATE, 250_000));
        assertThat(p.liabilitiesAndEquityMinor()).isEqualTo(5_250_000);
        assertThat(p.balanced()).isTrue();
    }

    @Test
    void theIncomeStatementSetsIncomeAgainstExpenses() {
        FinancialStatements.Performance p = FinancialStatements.performance(BOOKS);

        assertThat(p.income().totalMinor()).isEqualTo(350_000);
        assertThat(p.income().lines()).extracting(Line::code).containsExactly("4000", "4100");
        assertThat(p.expenses().totalMinor()).isEqualTo(100_000);
        assertThat(p.surplusMinor()).isEqualTo(250_000);
    }

    @Test
    void aDeficitReducesEquity() {
        FinancialStatements.Position p = FinancialStatements.position(List.of(
                new AccountBalance("1000", "Cash on hand", "ASSET", 400_000),
                new AccountBalance("2000", "Member savings", "LIABILITY", 500_000),
                new AccountBalance("5000", "Operating expenses", "EXPENSE", 100_000)));

        assertThat(p.equity().totalMinor()).isEqualTo(-100_000);
        assertThat(p.balanced()).isTrue();
        assertThat(FinancialStatements.performance(List.of()).surplusMinor()).isZero();
    }
}
