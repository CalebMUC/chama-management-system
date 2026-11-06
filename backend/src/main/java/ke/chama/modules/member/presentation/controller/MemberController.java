package ke.chama.modules.member.presentation.controller;

import ke.chama.shared.web.ApiResponse;
import java.util.List;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import ke.chama.modules.member.application.port.in.MemberViews.MemberView;
import ke.chama.modules.member.application.port.in.MemberViews.MyMembershipView;
import ke.chama.modules.member.application.port.in.MembershipCommands;
import ke.chama.modules.member.application.port.in.MembershipQueries;
import ke.chama.modules.member.domain.model.MembershipStatus;
import ke.chama.modules.member.presentation.dto.request.MemberRequests.ChangeRolesRequest;
import ke.chama.modules.member.presentation.dto.request.MemberRequests.InviteMemberRequest;
import ke.chama.modules.member.presentation.dto.request.MemberRequests.SuspendMemberRequest;
import ke.chama.shared.security.ChamaPermission;
import ke.chama.shared.security.CurrentUser;
import ke.chama.shared.security.RequiresChamaMembership;
import ke.chama.shared.security.RequiresChamaPermission;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * <pre>
 * POST /api/v1/chamas/{chamaId}/members/invitations          MEMBER_INVITE        {email}
 * POST /api/v1/chamas/{chamaId}/members/{id}/accept          the invitee (verified email)
 * POST /api/v1/chamas/{chamaId}/members/{id}/decline         the invitee
 * POST /api/v1/chamas/{chamaId}/members/{id}/approve         MEMBER_APPROVE
 * POST /api/v1/chamas/{chamaId}/members/{id}/reject          MEMBER_APPROVE
 * POST /api/v1/chamas/{chamaId}/members/{id}/suspend         MEMBER_SUSPEND       {reason}
 * POST /api/v1/chamas/{chamaId}/members/{id}/reactivate      MEMBER_SUSPEND
 * PUT  /api/v1/chamas/{chamaId}/members/{id}/roles           MEMBER_ROLES_MANAGE  {roles}
 * GET  /api/v1/chamas/{chamaId}/members?status=&page=&size=  MEMBER_VIEW
 * GET  /api/v1/chamas/{chamaId}/members/me                   active member: own membership + permissions
 * </pre>
 */
@Tag(name = "Members", description = "Invitations, approvals, suspensions and roles within a chama")
@RestController
@RequestMapping("/api/v1/chamas/{chamaId}/members")
class MemberController {

    private static final int MAX_PAGE_SIZE = 100;

    private final MembershipCommands commands;
    private final MembershipQueries queries;

    MemberController(MembershipCommands commands, MembershipQueries queries) {
        this.commands = commands;
        this.queries = queries;
    }

    @PostMapping("/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresChamaPermission(ChamaPermission.MEMBER_INVITE)
    ApiResponse<MemberView> invite(@PathVariable UUID chamaId, @Valid @RequestBody InviteMemberRequest request) {
        return ApiResponse.ok(commands.invite(chamaId, CurrentUser.id(), request.email()), "Invitation sent");
    }

    /** Not permission-protected: the invitee is not a member yet. The service checks it is their invitation. */
    @PostMapping("/{membershipId}/accept")
    ApiResponse<MemberView> accept(@PathVariable UUID chamaId, @PathVariable UUID membershipId) {
        return ApiResponse.ok(commands.accept(chamaId, membershipId, CurrentUser.id()),
                "Invitation accepted. A chama official will approve your membership.");
    }

    @PostMapping("/{membershipId}/decline")
    ApiResponse<MemberView> decline(@PathVariable UUID chamaId, @PathVariable UUID membershipId) {
        return ApiResponse.ok(commands.decline(chamaId, membershipId, CurrentUser.id()), "Invitation declined");
    }

    @PostMapping("/{membershipId}/approve")
    @RequiresChamaPermission(ChamaPermission.MEMBER_APPROVE)
    ApiResponse<MemberView> approve(@PathVariable UUID chamaId, @PathVariable UUID membershipId) {
        return ApiResponse.ok(commands.approve(chamaId, membershipId, CurrentUser.id()), "Member approved");
    }

    @PostMapping("/{membershipId}/reject")
    @RequiresChamaPermission(ChamaPermission.MEMBER_APPROVE)
    ApiResponse<MemberView> reject(@PathVariable UUID chamaId, @PathVariable UUID membershipId) {
        return ApiResponse.ok(commands.reject(chamaId, membershipId, CurrentUser.id()), "Membership request rejected");
    }

    @PostMapping("/{membershipId}/suspend")
    @RequiresChamaPermission(ChamaPermission.MEMBER_SUSPEND)
    ApiResponse<MemberView> suspend(@PathVariable UUID chamaId, @PathVariable UUID membershipId,
                                    @Valid @RequestBody SuspendMemberRequest request) {
        return ApiResponse.ok(commands.suspend(chamaId, membershipId, CurrentUser.id(), request.reason()),
                "Member suspended");
    }

    @PostMapping("/{membershipId}/reactivate")
    @RequiresChamaPermission(ChamaPermission.MEMBER_SUSPEND)
    ApiResponse<MemberView> reactivate(@PathVariable UUID chamaId, @PathVariable UUID membershipId) {
        return ApiResponse.ok(commands.reactivate(chamaId, membershipId, CurrentUser.id()), "Member reactivated");
    }

    @PutMapping("/{membershipId}/roles")
    @RequiresChamaPermission(ChamaPermission.MEMBER_ROLES_MANAGE)
    ApiResponse<MemberView> changeRoles(@PathVariable UUID chamaId, @PathVariable UUID membershipId,
                                        @Valid @RequestBody ChangeRolesRequest request) {
        return ApiResponse.ok(commands.changeRoles(chamaId, membershipId, CurrentUser.id(), request.roles()),
                "Roles updated");
    }

    @GetMapping
    @RequiresChamaPermission(ChamaPermission.MEMBER_VIEW)
    ApiResponse<List<MemberView>> list(@PathVariable UUID chamaId,
                                       @RequestParam(required = false) MembershipStatus status,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "50") int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Order.asc("memberNumber").nullsLast(), Sort.Order.asc("invitedAt")));
        return ApiResponse.page(queries.members(chamaId, status, pageable));
    }

    @GetMapping("/me")
    @RequiresChamaMembership
    ApiResponse<MyMembershipView> me(@PathVariable UUID chamaId) {
        return ApiResponse.ok(queries.myMembership(chamaId, CurrentUser.id()));
    }
}
