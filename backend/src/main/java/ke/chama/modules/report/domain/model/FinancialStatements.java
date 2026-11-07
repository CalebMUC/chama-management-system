package ke.chama.modules.report.domain.model;

import java.util.ArrayList;
import java.util.List;

/**
 * The two financial statements, built from account balances in the ledger.
 * <p>
 * The books are never closed at year end, so income and expenses to date are still in their own
 * accounts. The statement of financial position therefore shows their difference as "surplus to
 * date" within equity, which makes assets equal liabilities plus equity.
 */
public final class FinancialStatements {

    public static final String ASSET = "ASSET";
    public static final String LIABILITY = "LIABILITY";
    public static final String EQUITY = "EQUITY";
    public static final String INCOME = "INCOME";
    public static final String EXPENSE = "EXPENSE";
    public static final String SURPLUS_TO_DATE = "Surplus to date (income less expenses, not yet closed)";

    private FinancialStatements() {
    }

    /** One account's balance on its normal side (e.g. a positive asset, a positive liability). */
    public record AccountBalance(String code, String name, String type, long balanceMinor) {
    }

    /** @param lines accounts with a balance, by code; {@code code} is null for a computed line */
    public record Section(List<Line> lines, long totalMinor) {

        static Section of(List<Line> lines) {
            return new Section(List.copyOf(lines), lines.stream().mapToLong(Line::amountMinor).sum());
        }
    }

    public record Line(String code, String name, long amountMinor) {
    }

    /** @param balanced assets equal liabilities plus equity, as they always should */
    public record Position(Section assets, Section liabilities, Section equity, long liabilitiesAndEquityMinor,
                           boolean balanced) {
    }

    /** @param surplusMinor income less expenses; negative is a deficit */
    public record Performance(Section income, Section expenses, long surplusMinor) {
    }

    /** From every account's balance as at a date. */
    public static Position position(List<AccountBalance> balances) {
        Section assets = Section.of(lines(balances, ASSET));
        Section liabilities = Section.of(lines(balances, LIABILITY));
        List<Line> equityLines = new ArrayList<>(lines(balances, EQUITY));
        long surplus = total(balances, INCOME) - total(balances, EXPENSE);
        if (surplus != 0) {
            equityLines.add(new Line(null, SURPLUS_TO_DATE, surplus));
        }
        Section equity = Section.of(equityLines);
        long liabilitiesAndEquity = liabilities.totalMinor() + equity.totalMinor();
        return new Position(assets, liabilities, equity, liabilitiesAndEquity, assets.totalMinor() == liabilitiesAndEquity);
    }

    /** From every account's movements over a period. */
    public static Performance performance(List<AccountBalance> movements) {
        Section income = Section.of(lines(movements, INCOME));
        Section expenses = Section.of(lines(movements, EXPENSE));
        return new Performance(income, expenses, income.totalMinor() - expenses.totalMinor());
    }

    private static List<Line> lines(List<AccountBalance> balances, String type) {
        return balances.stream()
                .filter(b -> type.equals(b.type()) && b.balanceMinor() != 0)
                .map(b -> new Line(b.code(), b.name(), b.balanceMinor()))
                .toList();
    }

    private static long total(List<AccountBalance> balances, String type) {
        return balances.stream().filter(b -> type.equals(b.type())).mapToLong(AccountBalance::balanceMinor).sum();
    }
}
