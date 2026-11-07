package ke.chama.modules.report.presentation.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import ke.chama.modules.report.application.port.in.DashboardUseCases;
import ke.chama.modules.report.application.port.in.DashboardViews.ChamaDashboardView;
import ke.chama.modules.report.application.port.in.DashboardViews.MemberDashboardView;
import ke.chama.shared.security.ChamaPermission;
import ke.chama.shared.security.CurrentUser;
import ke.chama.shared.security.RequiresChamaMembership;
import ke.chama.shared.security.RequiresChamaPermission;
import ke.chama.shared.tenancy.TenantContext;
import ke.chama.shared.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * At-a-glance figures for the caller's chama. Every chama has a dashboard whatever its licence;
 * sections for features the licence does not include are null.
 * <pre>
 * GET /api/v1/dashboard        REPORT_VIEW_ALL   the chama: members, cash, savings, contributions, loans,
 *                                                penalties, investments, this month and the year so far,
 *                                                what is waiting for officials, latest ledger entries
 * GET /api/v1/dashboard/me     active member     my savings, contributions, loans and penalties
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
                    Members, cash on hand / bank / M-Pesa, savings per product, the current contribution period \
                    and arrears, the loan book and portfolio at risk, penalties owed, investments, income and \
                    expenses this month and this year, things waiting for officials, and the latest ledger \
                    entries. Sections the chama's licence does not include are null.""")
    @GetMapping
    @RequiresChamaPermission(ChamaPermission.REPORT_VIEW_ALL)
    ApiResponse<ChamaDashboardView> chama() {
        return ApiResponse.ok(dashboards.chama(TenantContext.requireChama()));
    }

    @Operation(summary = "My dashboard",
            description = "My savings per account, contribution standing, loans (with the next instalment) and penalties owed.")
    @GetMapping("/me")
    @RequiresChamaMembership
    ApiResponse<MemberDashboardView> mine() {
        return ApiResponse.ok(dashboards.member(TenantContext.requireChama(), CurrentUser.id()));
    }
}
