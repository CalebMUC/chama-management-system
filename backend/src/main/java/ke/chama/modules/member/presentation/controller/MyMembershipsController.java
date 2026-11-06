package ke.chama.modules.member.presentation.controller;

import ke.chama.shared.web.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import ke.chama.modules.member.application.port.in.MemberViews.InvitationView;
import ke.chama.modules.member.application.port.in.MemberViews.MyChamaView;
import ke.chama.modules.member.application.port.in.MembershipQueries;
import ke.chama.shared.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * <pre>
 * GET /api/v1/me/chamas        chamas I belong to, with my roles
 * GET /api/v1/me/invitations   open invitations sent to my email
 * </pre>
 */
@Tag(name = "My chamas", description = "The caller's chamas and pending invitations")
@RestController
@RequestMapping("/api/v1/me")
class MyMembershipsController {

    private final MembershipQueries queries;

    MyMembershipsController(MembershipQueries queries) {
        this.queries = queries;
    }

    @GetMapping("/chamas")
    ApiResponse<List<MyChamaView>> chamas() {
        return ApiResponse.ok(queries.myChamas(CurrentUser.id()));
    }

    @GetMapping("/invitations")
    ApiResponse<List<InvitationView>> invitations() {
        return ApiResponse.ok(queries.myInvitations(CurrentUser.id()));
    }
}
