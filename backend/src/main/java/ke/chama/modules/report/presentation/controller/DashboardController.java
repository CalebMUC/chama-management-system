package ke.chama.modules.report.presentation.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import ke.chama.modules.report.application.port.in.DashboardUseCases;
import ke.chama.modules.report.application.port.in.DashboardViews.ChamaDashboardView;
import ke.chama.modules.report.application.port.in.DashboardViews.ChamaOverviewView;
import ke.chama.modules.report.application.port.in.DashboardViews.MemberDashboardView;
import ke.chama.modules.report.application.port.in.DashboardViews.TopInvestmentsView;
import ke.chama.modules.report.application.port.in.DashboardViews.TopMembersView;
import ke.chama.modules.report.application.port.in.DashboardViews.TrendsView;
import ke.chama.shared.security.ChamaPermission;
import ke.chama.shared.security.CurrentUser;
import ke.chama.shared.security.RequiresChamaMembership;
import ke.chama.shared.security.RequiresChamaPermission;
import ke.chama.shared.tenancy.LicenseFeature;
import ke.chama.shared.tenancy.RequiresFeature;
import ke.chama.shared.tenancy.TenantContext;
import ke.chama.shared.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * At-a-glance figures for the caller's chama. Every chama has a dashboard whatever its licence;
 * sections for features the licence does not include are null.
 * <pre>
 * GET /api/v1/dashboard                  REPORT_VIEW_ALL   the chama: key figures, members, cash, savings, contributions,
 *                                                          loans, penalties, investments, top members, top investments,
 *                                                          this month / year to date, six-month trend, work queue,
 *                                                          latest ledger entries
 * GET /api/v1/dashboard/overview         CHAMA_VIEW        the chama's totals for every member: no names, ledger lines
 *                                                          or work queue; investments only with INVESTMENT_VIEW
 * GET /api/v1/dashboard/top-members      REPORT_VIEW_ALL   ?from=&to=&limit=  members ranked by contributions (CONTRIBUTIONS)
 * GET /api/v1/dashboard/top-investments  INVESTMENT_VIEW   ?limit=            investments ranked by return (INVESTMENTS)
 * GET /api/v1/dashboard/trends           REPORT_VIEW_ALL   ?months=           month-by-month money in and out
 * GET /api/v1/dashboard/me               active member     my savings, contributions, loans, penalties, reports, net position
 * </pre>
 */
@Tag(name = "Dashboard", description = "The chama's key figures for officials, and each member's own position")
@RestController
@RequestMapping("/api/v1/dashboard")
class DashboardController {

    private final DashboardUseCases dashboards;

    DashboardController(DashboardUseCases dashboards) {
        this.dashboards = dashboards;
    }

    @Operation(summary = "The chama's dashboard",
            description = """
                    Key figures (total contributions, savings, cash, loans outstanding, investments, penalties, assets, \
                    net worth); members; cash on hand / bank / M-Pesa; savings per product; the current period, \
                    arrears, compliance and what was collected this month and year; the loan book with ageing, \
                    PAR30 and what falls due this week; penalties owed, collected and waived and who owes most; \
                    investments with their returns; the top five contributors and investments; income and expenses \
                    this month, this year and for the last six months; what waits for officials (including payments \
                    members reported); and the latest ledger entries. Sections the licence does not include are null.""")
    @GetMapping
    @RequiresChamaPermission(ChamaPermission.REPORT_VIEW_ALL)
    ApiResponse<ChamaDashboardView> chama() {
        return ApiResponse.ok(dashboards.chama(TenantContext.requireChama()));
    }

    @Operation(summary = "The chama overview",
            description = """
                    How the chama is doing, for every member: key figures, member counts, cash, savings per product, \
                    the current contribution period and collection rate, the loan book, penalty totals, this month / \
                    year to date and the six-month trend. Totals only: nothing names a member (no top contributors, \
                    no list of who owes penalties, no ledger entries, no work queue). Investment figures need \
                    INVESTMENT_VIEW. Sections the licence does not include are null.""")
    @GetMapping("/overview")
    @RequiresChamaPermission(ChamaPermission.CHAMA_VIEW)
    ApiResponse<ChamaOverviewView> overview() {
        return ApiResponse.ok(dashboards.overview(TenantContext.requireChama(), CurrentUser.id()));
    }

    @Operation(summary = "Top-performing members",
            description = """
                    Members ranked by what they contributed between from and to (default: 1 January to today), then \
                    by the share of their dues paid in full. Each with payments, arrears, late periods and savings.""")
    @GetMapping("/top-members")
    @RequiresChamaPermission(ChamaPermission.REPORT_VIEW_ALL)
    @RequiresFeature(LicenseFeature.CONTRIBUTIONS)
    ApiResponse<TopMembersView> topMembers(@RequestParam(required = false) LocalDate from,
                                           @RequestParam(required = false) LocalDate to,
                                           @RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(dashboards.topMembers(TenantContext.requireChama(), from, to, limit));
    }

    @Operation(summary = "Top-performing investments",
            description = """
                    Funded investments (active and closed) ranked by total return: income less expenses plus the gain \
                    in value, as a share of what was put in. Each links to GET /investments/{id} for its detail.""")
    @GetMapping("/top-investments")
    @RequiresChamaPermission(ChamaPermission.INVESTMENT_VIEW)
    @RequiresFeature(LicenseFeature.INVESTMENTS)
    ApiResponse<TopInvestmentsView> topInvestments(@RequestParam(defaultValue = "5") int limit) {
        return ApiResponse.ok(dashboards.topInvestments(TenantContext.requireChama(), limit));
    }

    @Operation(summary = "Monthly trends",
            description = "The last 1-24 months (default 12), oldest first: contributions, loans disbursed, income, expenses and surplus.")
    @GetMapping("/trends")
    @RequiresChamaPermission(ChamaPermission.REPORT_VIEW_ALL)
    ApiResponse<TrendsView> trends(@RequestParam(defaultValue = "12") int months) {
        return ApiResponse.ok(dashboards.trends(TenantContext.requireChama(), months));
    }

    @Operation(summary = "My dashboard",
            description = """
                    My net position; savings per account; contribution standing, this year's total, compliance and my \
                    rank; each of my loans with its progress and next instalment; penalties I owe; payments I \
                    reported that wait for review; and, if I may see investments, the chama's top three.""")
    @GetMapping("/me")
    @RequiresChamaMembership
    ApiResponse<MemberDashboardView> mine() {
        return ApiResponse.ok(dashboards.member(TenantContext.requireChama(), CurrentUser.id()));
    }
}
