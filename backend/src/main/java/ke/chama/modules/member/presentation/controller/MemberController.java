package ke.chama.modules.member.presentation.controller;

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
import ke.chama.shared.web.PageResponse;
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
    MemberView invite(@PathVariable UUID chamaId, @Valid @RequestBody InviteMemberRequest request) {
        return commands.invite(chamaId, CurrentUser.id(), request.email());
    }

    /** Not permission-protected: the invitee is not a member yet. The service checks it is their invitation. */
    @PostMapping("/{membershipId}/accept")
    MemberView accept(@PathVariable UUID chamaId, @PathVariable UUID membershipId) {
        return commands.accept(chamaId, membershipId, CurrentUser.id());
    }

    @PostMapping("/{membershipId}/decline")
    MemberView decline(@PathVariable UUID chamaId, @PathVariable UUID membershipId) {
        return commands.decline(chamaId, membershipId, CurrentUser.id());
    }

    @PostMapping("/{membershipId}/approve")
    @RequiresChamaPermission(ChamaPermission.MEMBER_APPROVE)
    MemberView approve(@PathVariable UUID chamaId, @PathVariable UUID membershipId) {
        return commands.approve(chamaId, membershipId, CurrentUser.id());
    }

    @PostMapping("/{membershipId}/reject")
    @RequiresChamaPermission(ChamaPermission.MEMBER_APPROVE)
    MemberView reject(@PathVariable UUID chamaId, @PathVariable UUID membershipId) {
        return commands.reject(chamaId, membershipId, CurrentUser.id());
    }

    @PostMapping("/{membershipId}/suspend")
    @RequiresChamaPermission(ChamaPermission.MEMBER_SUSPEND)
    MemberView suspend(@PathVariable UUID chamaId, @PathVariable UUID membershipId,
                       @Valid @RequestBody SuspendMemberRequest request) {
        return commands.suspend(chamaId, membershipId, CurrentUser.id(), request.reason());
    }

    @PostMapping("/{membershipId}/reactivate")
    @RequiresChamaPermission(ChamaPermission.MEMBER_SUSPEND)
    MemberView reactivate(@PathVariable UUID chamaId, @PathVariable UUID membershipId) {
        return commands.reactivate(chamaId, membershipId, CurrentUser.id());
    }

    @PutMapping("/{membershipId}/roles")
    @RequiresChamaPermission(ChamaPermission.MEMBER_ROLES_MANAGE)
    MemberView changeRoles(@PathVariable UUID chamaId, @PathVariable UUID membershipId,
                           @Valid @RequestBody ChangeRolesRequest request) {
        return commands.changeRoles(chamaId, membershipId, CurrentUser.id(), request.roles());
    }

    @GetMapping
    @RequiresChamaPermission(ChamaPermission.MEMBER_VIEW)
    PageResponse<MemberView> list(@PathVariable UUID chamaId,
                                  @RequestParam(required = false) MembershipStatus status,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "50") int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Order.asc("memberNumber").nullsLast(), Sort.Order.asc("invitedAt")));
        return PageResponse.from(queries.members(chamaId, status, pageable));
    }

    @GetMapping("/me")
    @RequiresChamaMembership
    MyMembershipView me(@PathVariable UUID chamaId) {
        return queries.myMembership(chamaId, CurrentUser.id());
    }
}
