package ke.chama.modules.report.presentation.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import ke.chama.modules.report.application.port.in.ReportUseCases;
import ke.chama.modules.report.application.port.in.ReportViews.CashBookView;
import ke.chama.modules.report.application.port.in.ReportViews.ContributionReportView;
import ke.chama.modules.report.application.port.in.ReportViews.FinancialPositionView;
import ke.chama.modules.report.application.port.in.ReportViews.IncomeStatementView;
import ke.chama.modules.report.application.port.in.ReportViews.LoanPortfolioView;
import ke.chama.modules.report.application.port.in.ReportViews.MemberStatementView;
import ke.chama.modules.report.application.port.in.ReportViews.SavingsRegisterView;
import ke.chama.shared.security.ChamaPermission;
import ke.chama.shared.security.CurrentUser;
import ke.chama.shared.security.RequiresChamaMembership;
import ke.chama.shared.security.RequiresChamaPermission;
import ke.chama.shared.tenancy.LicenseFeature;
import ke.chama.shared.tenancy.RequiresFeature;
import ke.chama.shared.tenancy.TenantContext;
import ke.chama.shared.web.ApiResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Reports of the caller's chama: financial statements, the cash book, contributions, the loan book,
 * savings and member statements. Read-only. Needs the REPORTS licence feature (every plan has it).
 * <pre>
 * GET /api/v1/reports/financial-position              REPORT_VIEW_ALL  ?asOf=          balance sheet (today by default)
 * GET /api/v1/reports/income-statement                REPORT_VIEW_ALL  ?from=&to=      income and expenses (year to date)
 * GET /api/v1/reports/cash-book                       REPORT_VIEW_ALL  ?account=&from=&to=  cash, bank, M-Pesa (this month)
 * GET /api/v1/reports/contributions                   REPORT_VIEW_ALL  ?from=&to=      members against periods
 * GET /api/v1/reports/loan-portfolio                  REPORT_VIEW_ALL                  loans aged by days overdue, PAR30
 * GET /api/v1/reports/savings                         REPORT_VIEW_ALL                  every member's balance per product
 * GET /api/v1/reports/members/{userId}/statement      REPORT_VIEW_ALL  ?from=&to=      one member's statement
 * GET /api/v1/reports/members/me/statement            active member    ?from=&to=      my own statement
 * </pre>
 */
@Tag(name = "Reports", description = "Financial statements, cash book, contributions, loan book, savings and member statements")
@RequiresFeature(LicenseFeature.REPORTS)
@RestController
@RequestMapping("/api/v1/reports")
class ReportController {

    private final ReportUseCases reports;

    ReportController(ReportUseCases reports) {
        this.reports = reports;
    }

    @Operation(summary = "Statement of financial position",
            description = """
                    The balance sheet as at a date (today by default): assets, liabilities (mostly what the chama \
                    owes its members) and equity. Income less expenses not yet closed shows as surplus to date \
                    within equity, so assets always equal liabilities plus equity.""")
    @GetMapping("/financial-position")
    @RequiresChamaPermission(ChamaPermission.REPORT_VIEW_ALL)
    ApiResponse<FinancialPositionView> financialPosition(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return ApiResponse.ok(reports.financialPosition(chama(), asOf));
    }

    @Operation(summary = "Income statement",
            description = "Income and expenses per account between two dates (the year to date by default), and the surplus or deficit.")
    @GetMapping("/income-statement")
    @RequiresChamaPermission(ChamaPermission.REPORT_VIEW_ALL)
    ApiResponse<IncomeStatementView> incomeStatement(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(reports.incomeStatement(chama(), from, to));
    }

    @Operation(summary = "Cash book",
            description = """
                    Every receipt and payment through cash on hand, the bank and M-Pesa between two dates (this \
                    month by default), with opening and closing balances. 'account' narrows it to one of them by \
                    ledger code, e.g. 1020 for M-Pesa.""")
    @GetMapping("/cash-book")
    @RequiresChamaPermission(ChamaPermission.REPORT_VIEW_ALL)
    ApiResponse<CashBookView> cashBook(
            @RequestParam(required = false) String account,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(reports.cashBook(chama(), account, from, to));
    }

    @Operation(summary = "Contribution report",
            description = """
                    Every member against every contribution period (optionally only periods due between from and \
                    to): what they owed and paid for each, and their totals and arrears.""")
    @GetMapping("/contributions")
    @RequiresChamaPermission(ChamaPermission.REPORT_VIEW_ALL)
    ApiResponse<ContributionReportView> contributions(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(reports.contributions(chama(), from, to));
    }

    @Operation(summary = "Loan portfolio",
            description = """
                    Active loans aged by how long their oldest unpaid instalment has been overdue (up to date, \
                    1-30, 31-60, 61-90, over 90 days), portfolio at risk over 30 days, and loans still awaiting \
                    approval or disbursement. Most overdue first.""")
    @GetMapping("/loan-portfolio")
    @RequiresChamaPermission(ChamaPermission.REPORT_VIEW_ALL)
    ApiResponse<LoanPortfolioView> loanPortfolio() {
        return ApiResponse.ok(reports.loanPortfolio(chama()));
    }

    @Operation(summary = "Savings register", description = "Every member's balance in every savings product, with totals.")
    @GetMapping("/savings")
    @RequiresChamaPermission(ChamaPermission.REPORT_VIEW_ALL)
    ApiResponse<SavingsRegisterView> savings() {
        return ApiResponse.ok(reports.savingsRegister(chama()));
    }

    @Operation(summary = "My statement",
            description = "Your savings accounts, loans, penalties and contribution standing, with every movement in the period.")
    @GetMapping("/members/me/statement")
    @RequiresChamaMembership
    ApiResponse<MemberStatementView> myStatement(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(reports.memberStatement(chama(), CurrentUser.id(), from, to));
    }

    @Operation(summary = "A member's statement",
            description = "The member's savings accounts, loans, penalties and contribution standing, with every movement in the period.")
    @GetMapping("/members/{memberUserId}/statement")
    @RequiresChamaPermission(ChamaPermission.REPORT_VIEW_ALL)
    ApiResponse<MemberStatementView> memberStatement(
            @PathVariable UUID memberUserId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(reports.memberStatement(chama(), memberUserId, from, to));
    }

    private static UUID chama() {
        return TenantContext.requireChama();
    }
}
