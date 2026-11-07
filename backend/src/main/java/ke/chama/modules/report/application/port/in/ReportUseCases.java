package ke.chama.modules.report.application.port.in;

import ke.chama.modules.report.application.port.in.ReportViews.CashBookView;
import ke.chama.modules.report.application.port.in.ReportViews.ContributionReportView;
import ke.chama.modules.report.application.port.in.ReportViews.FinancialPositionView;
import ke.chama.modules.report.application.port.in.ReportViews.IncomeStatementView;
import ke.chama.modules.report.application.port.in.ReportViews.LoanPortfolioView;
import ke.chama.modules.report.application.port.in.ReportViews.MemberStatementView;
import ke.chama.modules.report.application.port.in.ReportViews.SavingsRegisterView;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Read-only reports of the caller's chama, built from the ledger and the other modules' records.
 * Dates are optional: the defaults are stated per report.
 */
public interface ReportUseCases {

    /** As at {@code asOf}; today by default. */
    FinancialPositionView financialPosition(UUID chamaId, LocalDate asOf);

    /** From {@code from} to {@code to}; the year to date by default. */
    IncomeStatementView incomeStatement(UUID chamaId, LocalDate from, LocalDate to);

    /**
     * @param accountCode one cash account's code (e.g. 1020), or null for cash, bank and M-Pesa together
     *                    (from the first of this month to today by default)
     */
    CashBookView cashBook(UUID chamaId, String accountCode, LocalDate from, LocalDate to);

    /** Periods due between {@code from} and {@code to} (inclusive); every period by default. */
    ContributionReportView contributions(UUID chamaId, LocalDate from, LocalDate to);

    LoanPortfolioView loanPortfolio(UUID chamaId);

    SavingsRegisterView savingsRegister(UUID chamaId);

    /** One seated member's statement; movements from {@code from} to {@code to}, all of them by default. */
    MemberStatementView memberStatement(UUID chamaId, UUID memberUserId, LocalDate from, LocalDate to);
}
