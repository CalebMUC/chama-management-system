package ke.chama.modules.contribution.presentation.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import ke.chama.modules.contribution.application.port.in.ContributionUseCases;
import ke.chama.modules.contribution.application.port.in.ContributionViews.MyContributionsView;
import ke.chama.modules.contribution.application.port.in.ContributionViews.PaymentView;
import ke.chama.modules.contribution.application.port.in.ContributionViews.PeriodDetailView;
import ke.chama.modules.contribution.application.port.in.ContributionViews.PeriodSummaryView;
import ke.chama.modules.contribution.application.port.in.ContributionViews.StandingView;
import ke.chama.modules.contribution.domain.model.ContributionPayment;
import ke.chama.modules.contribution.presentation.dto.request.ContributionRequests.RecordPaymentRequest;
import ke.chama.modules.contribution.presentation.dto.request.ContributionRequests.ReversePaymentRequest;
import ke.chama.shared.security.ChamaPermission;
import ke.chama.shared.security.CurrentUser;
import ke.chama.shared.security.RequiresChamaMembership;
import ke.chama.shared.security.RequiresChamaPermission;
import ke.chama.shared.tenancy.LicenseFeature;
import ke.chama.shared.tenancy.RequiresFeature;
import ke.chama.shared.tenancy.TenantContext;
import ke.chama.shared.web.ApiResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Contributions of the caller's chama. Needs the CONTRIBUTIONS licence feature.
 * <pre>
 * POST /api/v1/contributions                   CONTRIBUTION_RECORD     record a payment (posts to the ledger)
 * GET  /api/v1/contributions                   CONTRIBUTION_VIEW_ALL   ?memberUserId=&from=&to=&status=&page=&size=
 * GET  /api/v1/contributions/{id}              CONTRIBUTION_VIEW_ALL
 * POST /api/v1/contributions/{id}/reverse      CONTRIBUTION_REVERSE    {reason}
 * GET  /api/v1/contributions/periods           CONTRIBUTION_VIEW_ALL   every period with collection totals
 * GET  /api/v1/contributions/periods/{id}      CONTRIBUTION_VIEW_ALL   who paid what for one period
 * GET  /api/v1/contributions/standings         CONTRIBUTION_VIEW_ALL   ?inArrears=true
 * GET  /api/v1/contributions/me                active member           own standing, periods and payments
 * </pre>
 */
@Tag(name = "Contributions", description = "Member contributions: record payments, periods, arrears")
@RequiresFeature(LicenseFeature.CONTRIBUTIONS)
@RestController
@RequestMapping("/api/v1/contributions")
class ContributionController {

    private static final int MAX_PAGE_SIZE = 100;

    private final ContributionUseCases contributions;

    ContributionController(ContributionUseCases contributions) {
        this.contributions = contributions;
    }

    @Operation(summary = "Record a contribution payment",
            description = """
                    Money received from a member. Posted to the ledger at once (cash, bank or M-Pesa \
                    against the member's savings) and emailed to the member as a receipt. An official \
                    cannot record their own contribution, and an M-Pesa code or bank slip can only be \
                    recorded once.""")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresChamaPermission(ChamaPermission.CONTRIBUTION_RECORD)
    ApiResponse<PaymentView> record(@Valid @RequestBody RecordPaymentRequest request) {
        return ApiResponse.ok(contributions.record(chama(), CurrentUser.id(), request.toCommand()), "Contribution recorded");
    }

    @Operation(summary = "List contribution payments (newest first)")
    @GetMapping
    @RequiresChamaPermission(ChamaPermission.CONTRIBUTION_VIEW_ALL)
    ApiResponse<List<PaymentView>> list(@RequestParam(required = false) UUID memberUserId,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                        @RequestParam(required = false) ContributionPayment.Status status,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.page(contributions.payments(chama(), memberUserId, from, to, status,
                PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE))));
    }

    @Operation(summary = "One contribution payment")
    @GetMapping("/{paymentId}")
    @RequiresChamaPermission(ChamaPermission.CONTRIBUTION_VIEW_ALL)
    ApiResponse<PaymentView> get(@PathVariable UUID paymentId) {
        return ApiResponse.ok(contributions.payment(chama(), paymentId));
    }

    @Operation(summary = "Reverse a contribution payment",
            description = "Marks the payment reversed and reverses its ledger entry. Needs a reason; audit-logged.")
    @PostMapping("/{paymentId}/reverse")
    @RequiresChamaPermission(ChamaPermission.CONTRIBUTION_REVERSE)
    ApiResponse<PaymentView> reverse(@PathVariable UUID paymentId, @Valid @RequestBody ReversePaymentRequest request) {
        return ApiResponse.ok(contributions.reverse(chama(), CurrentUser.id(), paymentId, request.reason()),
                "Contribution reversed");
    }

    @Operation(summary = "Contribution periods with collection totals (newest first)")
    @GetMapping("/periods")
    @RequiresChamaPermission(ChamaPermission.CONTRIBUTION_VIEW_ALL)
    ApiResponse<List<PeriodSummaryView>> periods() {
        return ApiResponse.ok(contributions.periods(chama()));
    }

    @Operation(summary = "Who has paid for a period")
    @GetMapping("/periods/{periodId}")
    @RequiresChamaPermission(ChamaPermission.CONTRIBUTION_VIEW_ALL)
    ApiResponse<PeriodDetailView> period(@PathVariable UUID periodId) {
        return ApiResponse.ok(contributions.period(chama(), periodId));
    }

    @Operation(summary = "Every member's standing", description = "Total paid, due to date, arrears and advance. Most in arrears first.")
    @GetMapping("/standings")
    @RequiresChamaPermission(ChamaPermission.CONTRIBUTION_VIEW_ALL)
    ApiResponse<List<StandingView>> standings(@RequestParam(defaultValue = "false") boolean inArrears) {
        return ApiResponse.ok(contributions.standings(chama(), inArrears));
    }

    @Operation(summary = "My contributions", description = "Your standing, every period's status, and your latest payments.")
    @GetMapping("/me")
    @RequiresChamaMembership
    ApiResponse<MyContributionsView> mine() {
        return ApiResponse.ok(contributions.mine(chama(), CurrentUser.id()));
    }

    private static UUID chama() {
        return TenantContext.requireChama();
    }
}
