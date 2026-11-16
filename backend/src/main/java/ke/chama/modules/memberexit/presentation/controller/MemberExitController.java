package ke.chama.modules.memberexit.presentation.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import ke.chama.modules.memberexit.application.port.in.ExitUseCases;
import ke.chama.modules.memberexit.application.port.in.ExitUseCases.ExitPreviewView;
import ke.chama.modules.memberexit.application.port.in.ExitUseCases.ExitView;
import ke.chama.modules.memberexit.application.port.in.ExitUseCases.Payout;
import ke.chama.modules.memberexit.domain.model.MemberExit;
import ke.chama.modules.memberexit.presentation.dto.request.ExitRequests.ApproveRequest;
import ke.chama.modules.memberexit.presentation.dto.request.ExitRequests.RejectRequest;
import ke.chama.modules.memberexit.presentation.dto.request.ExitRequests.SettleRequest;
import ke.chama.modules.memberexit.presentation.dto.request.ExitRequests.StartExitRequest;
import ke.chama.shared.security.ChamaPermission;
import ke.chama.shared.security.CurrentUser;
import ke.chama.shared.security.RequiresChamaMembership;
import ke.chama.shared.security.RequiresChamaPermission;
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
 * Members leaving the caller's chama.
 * <pre>
 * GET  /api/v1/member-exits/preview?memberUserId=  MEMBER_EXIT_REQUEST  the settlement today and what blocks the exit
 * POST /api/v1/member-exits                        MEMBER_EXIT_REQUEST  {memberUserId, reason}
 * GET  /api/v1/member-exits                        MEMBER_VIEW          ?status=&memberUserId=&page=&size=
 * GET  /api/v1/member-exits/mine                   active member        my own exits
 * GET  /api/v1/member-exits/{id}                   MEMBER_VIEW
 * POST /api/v1/member-exits/{id}/approve           MEMBER_EXIT_APPROVE  {note}
 * POST /api/v1/member-exits/{id}/reject            MEMBER_EXIT_APPROVE  {reason}
 * POST /api/v1/member-exits/{id}/cancel            MEMBER_EXIT_REQUEST  whoever started it
 * POST /api/v1/member-exits/{id}/settle            MEMBER_EXIT_SETTLE   {method, reference, settledOn}
 * </pre>
 */
@Tag(name = "Member exits", description = "A member leaving the chama: settlement of savings, loans and penalties, "
        + "approved by a quorum of officials, then paid out")
@RestController
@RequestMapping("/api/v1/member-exits")
class MemberExitController {

    private static final int MAX_PAGE_SIZE = 100;

    private final ExitUseCases exits;

    MemberExitController(ExitUseCases exits) {
        this.exits = exits;
    }

    @Operation(summary = "Preview a member's exit settlement",
            description = """
                    What the member would get if they left today: refundable savings paid back, less their \
                    outstanding loans and penalties; non-refundable savings (e.g. share capital, welfare) kept by the \
                    chama; no dividend for the year they leave. Lists anything that stops them leaving now: \
                    guarantees of other members' loans (transfer them first), loan applications in progress, \
                    debts beyond their refundable savings, being the only active chairperson.""")
    @GetMapping("/preview")
    @RequiresChamaPermission(ChamaPermission.MEMBER_EXIT_REQUEST)
    ApiResponse<ExitPreviewView> preview(@RequestParam UUID memberUserId) {
        return ApiResponse.ok(exits.preview(chama(), memberUserId));
    }

    @Operation(summary = "Start a member's exit",
            description = "Works out the settlement as it stands today and sends it to the chama's quorum of officials "
                    + "for approval. Refused while anything listed by the preview blocks the exit. Not for your own exit.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresChamaPermission(ChamaPermission.MEMBER_EXIT_REQUEST)
    ApiResponse<ExitView> start(@Valid @RequestBody StartExitRequest request) {
        return ApiResponse.ok(exits.request(chama(), CurrentUser.id(), request.memberUserId(), request.reason()),
                "Exit started; the settlement awaits approval");
    }

    @Operation(summary = "List member exits (newest first)")
    @GetMapping
    @RequiresChamaPermission(ChamaPermission.MEMBER_VIEW)
    ApiResponse<List<ExitView>> list(@RequestParam(required = false) MemberExit.Status status,
                                     @RequestParam(required = false) UUID memberUserId,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.page(exits.exits(chama(), status, memberUserId,
                PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE))));
    }

    @Operation(summary = "My exits", description = "Exits started for the caller, with their settlement.")
    @GetMapping("/mine")
    @RequiresChamaMembership
    ApiResponse<List<ExitView>> mine() {
        return ApiResponse.ok(exits.mine(chama(), CurrentUser.id()));
    }

    @Operation(summary = "One member exit", description = "With its settlement, approvals and payout.")
    @GetMapping("/{exitId}")
    @RequiresChamaPermission(ChamaPermission.MEMBER_VIEW)
    ApiResponse<ExitView> get(@PathVariable UUID exitId) {
        return ApiResponse.ok(exits.exit(chama(), exitId));
    }

    @Operation(summary = "Approve an exit settlement",
            description = "One official's approval; approved once the quorum of distinct officials approve. Whoever "
                    + "started the exit, and the member leaving, cannot approve it.")
    @PostMapping("/{exitId}/approve")
    @RequiresChamaPermission(ChamaPermission.MEMBER_EXIT_APPROVE)
    ApiResponse<ExitView> approve(@PathVariable UUID exitId, @Valid @RequestBody(required = false) ApproveRequest request) {
        ExitView exit = exits.approve(chama(), CurrentUser.id(), exitId, request == null ? null : request.note());
        return ApiResponse.ok(exit, exit.status() == MemberExit.Status.APPROVED ? "Exit approved"
                : "Approval recorded (" + exit.approvals().size() + " of " + exit.requiredApprovals() + ")");
    }

    @Operation(summary = "Reject an exit settlement", description = "Before it is paid out. Needs a reason.")
    @PostMapping("/{exitId}/reject")
    @RequiresChamaPermission(ChamaPermission.MEMBER_EXIT_APPROVE)
    ApiResponse<ExitView> reject(@PathVariable UUID exitId, @Valid @RequestBody RejectRequest request) {
        return ApiResponse.ok(exits.reject(chama(), CurrentUser.id(), exitId, request.reason()), "Exit rejected");
    }

    @Operation(summary = "Cancel an exit", description = "Only whoever started it, before it is paid out.")
    @PostMapping("/{exitId}/cancel")
    @RequiresChamaPermission(ChamaPermission.MEMBER_EXIT_REQUEST)
    ApiResponse<ExitView> cancel(@PathVariable UUID exitId) {
        return ApiResponse.ok(exits.cancel(chama(), CurrentUser.id(), exitId), "Exit cancelled");
    }

    @Operation(summary = "Pay out an approved exit settlement",
            description = """
                    Clears the member's loans and penalties from their refundable savings, moves non-refundable \
                    savings to the chama's retained surplus, pays the rest out (method and reference needed only when \
                    something is paid out; the account it is paid from must hold enough), closes the member's \
                    accounts and ends the membership (EXITED). Refused if the member's savings, loans or penalties \
                    changed since the settlement was approved.""")
    @PostMapping("/{exitId}/settle")
    @RequiresChamaPermission(ChamaPermission.MEMBER_EXIT_SETTLE)
    ApiResponse<ExitView> settle(@PathVariable UUID exitId, @Valid @RequestBody(required = false) SettleRequest request) {
        Payout payout = request == null ? new Payout(null, null, null) : request.toCommand();
        return ApiResponse.ok(exits.settle(chama(), CurrentUser.id(), exitId, payout),
                "Exit settled; the member has left the chama");
    }

    private static UUID chama() {
        return TenantContext.requireChama();
    }
}
