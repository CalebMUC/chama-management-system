package ke.chama.modules.penalty.presentation.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import ke.chama.modules.penalty.application.port.in.PenaltyUseCases;
import ke.chama.modules.penalty.application.port.in.PenaltyUseCases.MyPenaltiesView;
import ke.chama.modules.penalty.application.port.in.PenaltyUseCases.PenaltyDetailView;
import ke.chama.modules.penalty.application.port.in.PenaltyUseCases.PenaltyView;
import ke.chama.modules.penalty.domain.model.Penalty;
import ke.chama.modules.penalty.presentation.dto.request.PenaltyRequests.FineRequest;
import ke.chama.modules.penalty.presentation.dto.request.PenaltyRequests.PenaltyPaymentRequest;
import ke.chama.modules.penalty.presentation.dto.request.PenaltyRequests.WaiveRequest;
import ke.chama.shared.security.ChamaPermission;
import ke.chama.shared.security.CurrentUser;
import ke.chama.shared.security.RequiresChamaMembership;
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
 * Penalties of the caller's chama. Needs the PENALTIES licence feature.
 * <pre>
 * GET  /api/v1/penalties                 PENALTY_VIEW_ALL   ?memberUserId=&status=&page=&size=
 * GET  /api/v1/penalties/{id}            PENALTY_VIEW_ALL   with its payments
 * POST /api/v1/penalties/fines           PENALTY_RECORD     {memberUserId, amountMinor, reason}
 * POST /api/v1/penalties/{id}/payments   PENALTY_RECORD     {amountMinor, method, reference, paidOn}
 * POST /api/v1/penalties/{id}/waive      PENALTY_WAIVE      {reason}
 * POST /api/v1/penalties/assess          PENALTY_RECORD     penalise late contributions now
 * GET  /api/v1/penalties/me              active member      own penalties
 * </pre>
 */
@Tag(name = "Penalties", description = "Late-contribution penalties, fines, payments and waivers")
@RequiresFeature(LicenseFeature.PENALTIES)
@RestController
@RequestMapping("/api/v1/penalties")
class PenaltyController {

    private static final int MAX_PAGE_SIZE = 100;

    private final PenaltyUseCases penalties;

    PenaltyController(PenaltyUseCases penalties) {
        this.penalties = penalties;
    }

    @Operation(summary = "List penalties (newest first)",
            description = "Late contributions are penalised before listing, so the list is always current.")
    @GetMapping
    @RequiresChamaPermission(ChamaPermission.PENALTY_VIEW_ALL)
    ApiResponse<List<PenaltyView>> list(@RequestParam(required = false) UUID memberUserId,
                                        @RequestParam(required = false) Penalty.Status status,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.page(penalties.penalties(chama(), memberUserId, status,
                PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE))));
    }

    @Operation(summary = "One penalty with its payments")
    @GetMapping("/{penaltyId}")
    @RequiresChamaPermission(ChamaPermission.PENALTY_VIEW_ALL)
    ApiResponse<PenaltyDetailView> get(@PathVariable UUID penaltyId) {
        return ApiResponse.ok(penalties.penalty(chama(), penaltyId));
    }

    @Operation(summary = "Fine a member", description = "For something other than a late contribution, e.g. a missed meeting. Posted to the ledger.")
    @PostMapping("/fines")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresChamaPermission(ChamaPermission.PENALTY_RECORD)
    ApiResponse<PenaltyView> fine(@Valid @RequestBody FineRequest request) {
        return ApiResponse.ok(penalties.fine(chama(), CurrentUser.id(), request.memberUserId(), request.amountMinor(),
                request.reason()), "Fine recorded");
    }

    @Operation(summary = "Record a penalty payment", description = "Up to what is outstanding; a penalty can be paid in parts.")
    @PostMapping("/{penaltyId}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresChamaPermission(ChamaPermission.PENALTY_RECORD)
    ApiResponse<PenaltyView> pay(@PathVariable UUID penaltyId, @Valid @RequestBody PenaltyPaymentRequest request) {
        return ApiResponse.ok(penalties.recordPayment(chama(), CurrentUser.id(), penaltyId, request.toCommand()),
                "Penalty payment recorded");
    }

    @Operation(summary = "Waive a penalty", description = "Waives whatever is still outstanding. Needs a reason; audit-logged.")
    @PostMapping("/{penaltyId}/waive")
    @RequiresChamaPermission(ChamaPermission.PENALTY_WAIVE)
    ApiResponse<PenaltyView> waive(@PathVariable UUID penaltyId, @Valid @RequestBody WaiveRequest request) {
        return ApiResponse.ok(penalties.waive(chama(), CurrentUser.id(), penaltyId, request.reason()), "Penalty waived");
    }

    @Operation(summary = "Penalise late contributions now", description = "Normally done every night. Each late period is penalised once per member.")
    @PostMapping("/assess")
    @RequiresChamaPermission(ChamaPermission.PENALTY_RECORD)
    ApiResponse<List<PenaltyView>> assess() {
        List<PenaltyView> created = penalties.assessLateContributions(chama());
        return ApiResponse.ok(created, created.isEmpty() ? "No new penalties" : created.size() + " new penalt"
                + (created.size() == 1 ? "y" : "ies"));
    }

    @Operation(summary = "My penalties")
    @GetMapping("/me")
    @RequiresChamaMembership
    ApiResponse<MyPenaltiesView> mine() {
        return ApiResponse.ok(penalties.mine(chama(), CurrentUser.id()));
    }

    private static UUID chama() {
        return TenantContext.requireChama();
    }
}
