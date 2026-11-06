package ke.chama.modules.member.presentation.controller;

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
@RestController
@RequestMapping("/api/v1/me")
class MyMembershipsController {

    private final MembershipQueries queries;

    MyMembershipsController(MembershipQueries queries) {
        this.queries = queries;
    }

    @GetMapping("/chamas")
    List<MyChamaView> chamas() {
        return queries.myChamas(CurrentUser.id());
    }

    @GetMapping("/invitations")
    List<InvitationView> invitations() {
        return queries.myInvitations(CurrentUser.id());
    }
}
