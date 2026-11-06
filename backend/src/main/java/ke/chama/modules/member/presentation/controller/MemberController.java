package ke.chama.modules.member.presentation.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import ke.chama.modules.member.application.port.in.MemberViews.InvitationView;
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
import ke.chama.shared.security.RequiresChamaPermission;
import ke.chama.shared.tenancy.TenantContext;
import ke.chama.shared.web.ApiResponse;
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

import java.util.List;
import java.util.UUID;

/**
 * Members of the caller's chama (the tenant of their access token).
 * <pre>
 * POST /api/v1/members/invitations          MEMBER_INVITE        {email}   emails a join link
 * POST /api/v1/members/{id}/approve         MEMBER_APPROVE
 * POST /api/v1/members/{id}/reject          MEMBER_APPROVE
 * POST /api/v1/members/{id}/suspend         MEMBER_SUSPEND       {reason}
 * POST /api/v1/members/{id}/reactivate      MEMBER_SUSPEND
 * PUT  /api/v1/members/{id}/roles           MEMBER_ROLES_MANAGE  {roles}
 * GET  /api/v1/members?status=&page=&size=  MEMBER_VIEW
 * GET  /api/v1/members/me                   any user: own membership (any status) + permissions
 * </pre>
 */
@Tag(name = "Members", description = "Applications, approvals, suspensions and roles in your chama")
@RestController
@RequestMapping("/api/v1/members")
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
    ApiResponse<InvitationView> invite(@Valid @RequestBody InviteMemberRequest request) {
        return ApiResponse.ok(commands.invite(chama(), CurrentUser.id(), request.email()),
                "Invitation sent. They join by registering with your chama code, then an official approves them.");
    }

    @PostMapping("/{membershipId}/approve")
    @RequiresChamaPermission(ChamaPermission.MEMBER_APPROVE)
    ApiResponse<MemberView> approve(@PathVariable UUID membershipId) {
        return ApiResponse.ok(commands.approve(chama(), membershipId, CurrentUser.id()), "Member approved");
    }

    @PostMapping("/{membershipId}/reject")
    @RequiresChamaPermission(ChamaPermission.MEMBER_APPROVE)
    ApiResponse<MemberView> reject(@PathVariable UUID membershipId) {
        return ApiResponse.ok(commands.reject(chama(), membershipId, CurrentUser.id()), "Membership request rejected");
    }

    @PostMapping("/{membershipId}/suspend")
    @RequiresChamaPermission(ChamaPermission.MEMBER_SUSPEND)
    ApiResponse<MemberView> suspend(@PathVariable UUID membershipId, @Valid @RequestBody SuspendMemberRequest request) {
        return ApiResponse.ok(commands.suspend(chama(), membershipId, CurrentUser.id(), request.reason()),
                "Member suspended");
    }

    @PostMapping("/{membershipId}/reactivate")
    @RequiresChamaPermission(ChamaPermission.MEMBER_SUSPEND)
    ApiResponse<MemberView> reactivate(@PathVariable UUID membershipId) {
        return ApiResponse.ok(commands.reactivate(chama(), membershipId, CurrentUser.id()), "Member reactivated");
    }

    @PutMapping("/{membershipId}/roles")
    @RequiresChamaPermission(ChamaPermission.MEMBER_ROLES_MANAGE)
    ApiResponse<MemberView> changeRoles(@PathVariable UUID membershipId,
                                        @Valid @RequestBody ChangeRolesRequest request) {
        return ApiResponse.ok(commands.changeRoles(chama(), membershipId, CurrentUser.id(), request.roles()),
                "Roles updated");
    }

    @GetMapping
    @RequiresChamaPermission(ChamaPermission.MEMBER_VIEW)
    ApiResponse<List<MemberView>> list(@RequestParam(required = false) MembershipStatus status,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "50") int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Order.asc("memberNumber").nullsLast(), Sort.Order.asc("appliedAt")));
        return ApiResponse.page(queries.members(chama(), status, pageable));
    }

    /** Works in every status, so an applicant can see they are waiting for approval. */
    @GetMapping("/me")
    ApiResponse<MyMembershipView> me() {
        return ApiResponse.ok(queries.myMembership(chama(), CurrentUser.id()));
    }

    private static UUID chama() {
        return TenantContext.requireChama();
    }
}
