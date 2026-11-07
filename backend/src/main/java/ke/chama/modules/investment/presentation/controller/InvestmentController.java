package ke.chama.modules.investment.presentation.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import ke.chama.modules.investment.application.port.in.InvestmentUseCases;
import ke.chama.modules.investment.application.port.in.InvestmentUseCases.InvestmentDetailView;
import ke.chama.modules.investment.application.port.in.InvestmentUseCases.InvestmentView;
import ke.chama.modules.investment.application.port.in.InvestmentUseCases.PortfolioView;
import ke.chama.modules.investment.domain.model.Investment;
import ke.chama.modules.investment.presentation.dto.request.InvestmentRequests.ApproveRequest;
import ke.chama.modules.investment.presentation.dto.request.InvestmentRequests.CloseRequest;
import ke.chama.modules.investment.presentation.dto.request.InvestmentRequests.DepositRequest;
import ke.chama.modules.investment.presentation.dto.request.InvestmentRequests.FundRequest;
import ke.chama.modules.investment.presentation.dto.request.InvestmentRequests.IncomeRequest;
import ke.chama.modules.investment.presentation.dto.request.InvestmentRequests.ProposeRequest;
import ke.chama.modules.investment.presentation.dto.request.InvestmentRequests.RejectRequest;
import ke.chama.modules.investment.presentation.dto.request.InvestmentRequests.ValuationRequest;
import ke.chama.modules.investment.presentation.dto.request.InvestmentRequests.WithdrawalRequest;
import ke.chama.shared.security.ChamaPermission;
import ke.chama.shared.security.CurrentUser;
import ke.chama.shared.security.RequiresChamaPermission;
import ke.chama.shared.tenancy.LicenseFeature;
import ke.chama.shared.tenancy.RequiresFeature;
import ke.chama.shared.tenancy.TenantContext;
import ke.chama.shared.web.ApiResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Investments of the caller's chama. Needs the INVESTMENTS licence feature. Every member can see them.
 * <pre>
 * POST /api/v1/investments                   INVESTMENT_MANAGE   propose
 * GET  /api/v1/investments                   INVESTMENT_VIEW     ?status=&type=&page=&size=
 * GET  /api/v1/investments/portfolio         INVESTMENT_VIEW
 * GET  /api/v1/investments/{id}              INVESTMENT_VIEW     with transactions and valuations
 * POST /api/v1/investments/{id}/approve      INVESTMENT_APPROVE  {note}
 * POST /api/v1/investments/{id}/reject       INVESTMENT_APPROVE  {reason}
 * POST /api/v1/investments/{id}/cancel       INVESTMENT_MANAGE   whoever proposed it
 * POST /api/v1/investments/{id}/fund         INVESTMENT_MANAGE   {method, reference, fundedOn}
 * POST /api/v1/investments/{id}/deposits     INVESTMENT_MANAGE   {amountMinor, method, reference, depositedOn, note}
 * POST /api/v1/investments/{id}/income       INVESTMENT_MANAGE   {amountMinor, method, reference, receivedOn, note}
 * POST /api/v1/investments/{id}/withdrawals  INVESTMENT_MANAGE   {amountMinor, method, reference, withdrawnOn, note}
 * POST /api/v1/investments/{id}/valuations   INVESTMENT_MANAGE   {valueMinor, valuedOn, note}
 * POST /api/v1/investments/{id}/close        INVESTMENT_MANAGE   {proceedsMinor, method, reference, closedOn, note}
 * </pre>
 */
@Tag(name = "Investments", description = "Proposals approved by quorum, funding, income, valuations and closure")
@RequiresFeature(LicenseFeature.INVESTMENTS)
@RestController
@RequestMapping("/api/v1/investments")
class InvestmentController {

    private static final int MAX_PAGE_SIZE = 100;

    private final InvestmentUseCases investments;

    InvestmentController(InvestmentUseCases investments) {
        this.investments = investments;
    }

    @Operation(summary = "Propose an investment",
            description = "Awaits approval by the chama's quorum of officials other than you.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresChamaPermission(ChamaPermission.INVESTMENT_MANAGE)
    ApiResponse<InvestmentView> propose(@Valid @RequestBody ProposeRequest request) {
        return ApiResponse.ok(investments.propose(chama(), CurrentUser.id(), request.toCommand()),
                "Investment proposed for approval");
    }

    @Operation(summary = "List investments (newest first)")
    @GetMapping
    @RequiresChamaPermission(ChamaPermission.INVESTMENT_VIEW)
    ApiResponse<List<InvestmentView>> list(@RequestParam(required = false) Investment.Status status,
                                           @RequestParam(required = false) Investment.Type type,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.page(investments.investments(chama(), status, type,
                PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE))));
    }

    @Operation(summary = "Portfolio",
            description = "Active investments at book and market value, by type; income and realised gains to date; "
                    + "what matures in the next 30 days.")
    @GetMapping("/portfolio")
    @RequiresChamaPermission(ChamaPermission.INVESTMENT_VIEW)
    ApiResponse<PortfolioView> portfolio() {
        return ApiResponse.ok(investments.portfolio(chama()));
    }

    @Operation(summary = "One investment", description = "With its transactions and valuations.")
    @GetMapping("/{investmentId}")
    @RequiresChamaPermission(ChamaPermission.INVESTMENT_VIEW)
    ApiResponse<InvestmentDetailView> get(@PathVariable UUID investmentId) {
        return ApiResponse.ok(investments.investment(chama(), investmentId));
    }

    @Operation(summary = "Approve a proposed investment",
            description = "One official's approval; approved once the quorum of distinct officials approve. "
                    + "Whoever proposed it cannot approve it.")
    @PostMapping("/{investmentId}/approve")
    @RequiresChamaPermission(ChamaPermission.INVESTMENT_APPROVE)
    ApiResponse<InvestmentView> approve(@PathVariable UUID investmentId,
                                        @Valid @RequestBody(required = false) ApproveRequest request) {
        InvestmentView investment = investments.approve(chama(), CurrentUser.id(), investmentId,
                request == null ? null : request.note());
        return ApiResponse.ok(investment, investment.status() == Investment.Status.APPROVED ? "Investment approved"
                : "Approval recorded (" + investment.approvals().size() + " of " + investment.requiredApprovals() + ")");
    }

    @Operation(summary = "Reject a proposed investment", description = "Before it is funded. Needs a reason.")
    @PostMapping("/{investmentId}/reject")
    @RequiresChamaPermission(ChamaPermission.INVESTMENT_APPROVE)
    ApiResponse<InvestmentView> reject(@PathVariable UUID investmentId, @Valid @RequestBody RejectRequest request) {
        return ApiResponse.ok(investments.reject(chama(), CurrentUser.id(), investmentId, request.reason()),
                "Investment rejected");
    }

    @Operation(summary = "Cancel a proposal", description = "Only whoever proposed it, before it is funded.")
    @PostMapping("/{investmentId}/cancel")
    @RequiresChamaPermission(ChamaPermission.INVESTMENT_MANAGE)
    ApiResponse<InvestmentView> cancel(@PathVariable UUID investmentId) {
        return ApiResponse.ok(investments.cancel(chama(), CurrentUser.id(), investmentId), "Investment cancelled");
    }

    @Operation(summary = "Fund an approved investment",
            description = "Puts the approved amount in from cash, bank or M-Pesa, which must hold enough.")
    @PostMapping("/{investmentId}/fund")
    @RequiresChamaPermission(ChamaPermission.INVESTMENT_MANAGE)
    ApiResponse<InvestmentDetailView> fund(@PathVariable UUID investmentId, @Valid @RequestBody FundRequest request) {
        return ApiResponse.ok(investments.fund(chama(), CurrentUser.id(), investmentId, request.toCommand()),
                "Investment funded");
    }

    @Operation(summary = "Deposit more into an investment",
            description = "E.g. a monthly top-up of a money market fund. From cash, bank or M-Pesa, which must hold "
                    + "enough. No new approval: the investment was approved.")
    @PostMapping("/{investmentId}/deposits")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresChamaPermission(ChamaPermission.INVESTMENT_MANAGE)
    ApiResponse<InvestmentDetailView> deposit(@PathVariable UUID investmentId, @Valid @RequestBody DepositRequest request) {
        return ApiResponse.ok(investments.deposit(chama(), CurrentUser.id(), investmentId, request.toCommand()),
                "Deposit recorded");
    }

    @Operation(summary = "Record income",
            description = "Interest, dividends, rent or bookings, paid into cash, bank or M-Pesa, or REINVESTED "
                    + "(adds to the book value).")
    @PostMapping("/{investmentId}/income")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresChamaPermission(ChamaPermission.INVESTMENT_MANAGE)
    ApiResponse<InvestmentDetailView> income(@PathVariable UUID investmentId, @Valid @RequestBody IncomeRequest request) {
        return ApiResponse.ok(investments.recordIncome(chama(), CurrentUser.id(), investmentId, request.toCommand()),
                "Income recorded");
    }

    @Operation(summary = "Withdraw part of an investment",
            description = "Less than the book value; close the investment to take everything out.")
    @PostMapping("/{investmentId}/withdrawals")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresChamaPermission(ChamaPermission.INVESTMENT_MANAGE)
    ApiResponse<InvestmentDetailView> withdraw(@PathVariable UUID investmentId,
                                               @Valid @RequestBody WithdrawalRequest request) {
        return ApiResponse.ok(investments.withdraw(chama(), CurrentUser.id(), investmentId, request.toCommand()),
                "Withdrawal recorded");
    }

    @Operation(summary = "Record a valuation",
            description = "What it is worth now. Not posted: the books keep investments at cost until they are closed.")
    @PostMapping("/{investmentId}/valuations")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresChamaPermission(ChamaPermission.INVESTMENT_MANAGE)
    ApiResponse<InvestmentDetailView> value(@PathVariable UUID investmentId, @Valid @RequestBody ValuationRequest request) {
        return ApiResponse.ok(investments.value(chama(), CurrentUser.id(), investmentId, request.valueMinor(),
                request.valuedOn(), request.note()), "Valuation recorded");
    }

    @Operation(summary = "Close an investment",
            description = "Sold or redeemed. The proceeds less the book value is posted as a gain or loss.")
    @PostMapping("/{investmentId}/close")
    @RequiresChamaPermission(ChamaPermission.INVESTMENT_MANAGE)
    ApiResponse<InvestmentDetailView> close(@PathVariable UUID investmentId, @Valid @RequestBody CloseRequest request) {
        return ApiResponse.ok(investments.close(chama(), CurrentUser.id(), investmentId, request.toCommand()),
                "Investment closed");
    }

    private static UUID chama() {
        return TenantContext.requireChama();
    }
}
