package ke.chama.modules.member;

import com.jayway.jsonpath.JsonPath;
import ke.chama.IntegrationTest;
import ke.chama.RecordingEmailSender;
import ke.chama.TestAccounts;
import ke.chama.TestAccounts.Account;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Chama founding, membership lifecycle, per-chama permissions and the audit log, over HTTP. */
@IntegrationTest
class ChamaMembershipIntegrationTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    RecordingEmailSender emails;

    @Autowired
    JdbcTemplate jdbc;

    TestAccounts accounts;

    @BeforeEach
    void setUp() {
        accounts = new TestAccounts(mvc, emails);
    }

    @Nested
    class Founding {

        @Test
        void requiresAVerifiedEmail() throws Exception {
            Account unverified = accounts.unverified("Wanjiru");

            mvc.perform(unverified.post("/api/v1/chamas", "{\"name\":\"Umoja Group\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("CHAMA_EMAIL_NOT_VERIFIED"));
        }

        @Test
        void founderBecomesChairpersonWithEveryPermission() throws Exception {
            Account founder = accounts.verified("Achieng");

            mvc.perform(founder.post("/api/v1/chamas", "{\"name\":\"  Umoja Women Group \"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.name").value("Umoja Women Group"))
                    .andExpect(jsonPath("$.data.currency").value("KES"))
                    .andExpect(jsonPath("$.data.settings.contributionAmountMinor").value(500000))
                    .andExpect(jsonPath("$.data.settings.loanMaxMultiplier").value(3));
            UUID chamaId = latestChamaOf(founder);

            mvc.perform(founder.get("/api/v1/chamas/{id}/members/me", chamaId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.membership.memberNumber").value(1))
                    .andExpect(jsonPath("$.data.membership.roles").value(hasItems("MEMBER", "CHAIRPERSON")))
                    .andExpect(jsonPath("$.data.permissions").value(hasItems("ROLE_PERMISSIONS_MANAGE", "AUDIT_LOG_VIEW")));
        }

        @Test
        void rejectsInvalidSettings() throws Exception {
            Account founder = accounts.verified("Otieno");

            mvc.perform(founder.post("/api/v1/chamas", """
                            {"name":"Bad Rules","settings":{"contributionAmountMinor":500000,"contributionFrequency":"MONTHLY",
                             "contributionDueDay":31,"lateGraceDays":5,"latePenaltyBasisPoints":200,"loanMaxMultiplier":3,
                             "loanInterestBasisPoints":1000,"loanMinMembershipMonths":3,"loanGuarantorThresholdMinor":5000000,
                             "loanGuarantorsRequired":2,"approvalQuorum":2}}"""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("CHAMA_INVALID_SETTINGS"));
        }
    }

    @Nested
    class MembershipLifecycle {

        @Test
        void inviteAcceptApprove() throws Exception {
            Account chair = accounts.verified("Kamau");
            UUID chamaId = found(chair);
            Account jane = accounts.verified("Jane");

            String invited = mvc.perform(chair.post("/api/v1/chamas/{id}/members/invitations",
                            "{\"email\":\"%s\"}".formatted(jane.email().toUpperCase()), chamaId))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.status").value("INVITED"))
                    .andReturn().getResponse().getContentAsString();
            UUID membershipId = UUID.fromString(JsonPath.read(invited, "$.data.membershipId"));
            assertThat(emails.awaitEmail(jane.email(), s -> s.startsWith("You're invited")).body()).contains("Kamau Test");

            mvc.perform(jane.get("/api/v1/me/invitations"))
                    .andExpect(jsonPath("$.data[*].membershipId").value(hasItem(membershipId.toString())));

            mvc.perform(jane.post("/api/v1/chamas/{c}/members/{m}/accept", chamaId, membershipId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("PENDING_APPROVAL"));

            mvc.perform(chair.post("/api/v1/chamas/{c}/members/{m}/approve", chamaId, membershipId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.data.memberNumber").value(2))
                    .andExpect(jsonPath("$.data.firstName").value("Jane"));

            assertThat(emails.awaitEmail(jane.email(), s -> s.startsWith("Welcome")).body()).contains("member number is 2");
            mvc.perform(jane.get("/api/v1/me/chamas"))
                    .andExpect(jsonPath("$.data[*].chamaId").value(hasItem(chamaId.toString())));
        }

        @Test
        void invitationRequiresAVerifiedEmailToAccept() throws Exception {
            Account chair = accounts.verified("Mwangi");
            UUID chamaId = found(chair);
            Account unverified = accounts.unverified("Brian");
            UUID membershipId = invite(chair, chamaId, unverified.email());

            mvc.perform(unverified.post("/api/v1/chamas/{c}/members/{m}/accept", chamaId, membershipId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("MEMBER_EMAIL_NOT_VERIFIED"));
        }

        @Test
        void onlyTheInviteeCanAccept() throws Exception {
            Account chair = accounts.verified("Njoroge");
            UUID chamaId = found(chair);
            Account invitee = accounts.verified("Faith");
            Account intruder = accounts.verified("Mallory");
            UUID membershipId = invite(chair, chamaId, invitee.email());

            mvc.perform(intruder.post("/api/v1/chamas/{c}/members/{m}/accept", chamaId, membershipId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("MEMBER_INVITATION_NOT_YOURS"));
        }

        @Test
        void cannotInviteSomeoneTwice() throws Exception {
            Account chair = accounts.verified("Wekesa");
            UUID chamaId = found(chair);
            invite(chair, chamaId, "twice@example.com");

            mvc.perform(chair.post("/api/v1/chamas/{id}/members/invitations", "{\"email\":\"twice@example.com\"}", chamaId))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("MEMBER_ALREADY_EXISTS"));
        }
    }

    @Nested
    class Permissions {

        @Test
        void plainMembersCanReadButNotManage() throws Exception {
            Account chair = accounts.verified("Chebet");
            UUID chamaId = found(chair);
            Account member = accounts.verified("Kevin");
            activeMember(chair, chamaId, member);

            mvc.perform(member.get("/api/v1/chamas/{id}", chamaId)).andExpect(status().isOk());
            mvc.perform(member.get("/api/v1/chamas/{id}/members", chamaId)).andExpect(status().isOk());
            mvc.perform(member.put("/api/v1/chamas/{id}/settings", standardSettingsJson(), chamaId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
            mvc.perform(member.post("/api/v1/chamas/{id}/members/invitations", "{\"email\":\"x@example.com\"}", chamaId))
                    .andExpect(status().isForbidden());
            mvc.perform(member.get("/api/v1/chamas/{id}/audit-log", chamaId)).andExpect(status().isForbidden());
        }

        @Test
        void outsidersSeeNothing() throws Exception {
            Account chair = accounts.verified("Kiprop");
            UUID chamaId = found(chair);
            Account outsider = accounts.verified("Eve");

            mvc.perform(outsider.get("/api/v1/chamas/{id}", chamaId)).andExpect(status().isForbidden());
            mvc.perform(outsider.get("/api/v1/chamas/{id}", UUID.randomUUID())).andExpect(status().isForbidden());
        }

        @Test
        void treasurerGetsMoneyPermissionsButNotTheAuditLog() throws Exception {
            Account chair = accounts.verified("Auma");
            UUID chamaId = found(chair);
            Account treasurer = accounts.verified("Tom");
            UUID membershipId = activeMember(chair, chamaId, treasurer);

            mvc.perform(chair.put("/api/v1/chamas/{c}/members/{m}/roles", "{\"roles\":[\"TREASURER\"]}", chamaId, membershipId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.roles").value(hasItems("MEMBER", "TREASURER")));

            mvc.perform(treasurer.get("/api/v1/chamas/{id}/members/me", chamaId))
                    .andExpect(jsonPath("$.data.permissions").value(hasItems("CONTRIBUTION_RECORD", "LOAN_DISBURSE")))
                    .andExpect(jsonPath("$.data.permissions").value(not(hasItem("EXPENSE_APPROVE"))));
            mvc.perform(treasurer.get("/api/v1/chamas/{id}/audit-log", chamaId)).andExpect(status().isForbidden());
        }

        @Test
        void customRolePermissionsTakeEffectOnTheNextRequest() throws Exception {
            Account chair = accounts.verified("Nduta");
            UUID chamaId = found(chair);
            Account member = accounts.verified("Lucy");
            activeMember(chair, chamaId, member);
            mvc.perform(member.get("/api/v1/chamas/{id}/audit-log", chamaId)).andExpect(status().isForbidden());

            mvc.perform(chair.put("/api/v1/chamas/{id}/roles/MEMBER/permissions",
                            "{\"permissions\":[\"CHAMA_VIEW\",\"MEMBER_VIEW\",\"LOAN_APPLY\",\"AUDIT_LOG_VIEW\"]}", chamaId))
                    .andExpect(status().isOk());

            // Same access token as before: permissions come from the database, not the JWT.
            mvc.perform(member.get("/api/v1/chamas/{id}/audit-log", chamaId)).andExpect(status().isOk());
        }

        @Test
        void chairpersonRoleCannotLoseRoleManagement() throws Exception {
            Account chair = accounts.verified("Mutua");
            UUID chamaId = found(chair);

            mvc.perform(chair.put("/api/v1/chamas/{id}/roles/CHAIRPERSON/permissions",
                            "{\"permissions\":[\"CHAMA_VIEW\",\"MEMBER_ROLES_MANAGE\"]}", chamaId))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("ROLE_PERMISSIONS_LOCKOUT"));
        }
    }

    @Nested
    class SeparationOfDuties {

        @Test
        void nobodyChangesTheirOwnRoles() throws Exception {
            Account chair = accounts.verified("Omondi");
            UUID chamaId = found(chair);
            UUID ownMembership = UUID.fromString(JsonPath.read(mvc.perform(chair.get("/api/v1/chamas/{id}/members/me", chamaId))
                    .andReturn().getResponse().getContentAsString(), "$.data.membership.membershipId"));

            mvc.perform(chair.put("/api/v1/chamas/{c}/members/{m}/roles", "{\"roles\":[\"MEMBER\"]}", chamaId, ownMembership))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("MEMBER_SELF_MANAGEMENT"));
        }

        @Test
        void theLastChairpersonCannotBeRemoved() throws Exception {
            Account chair = accounts.verified("Wairimu");
            UUID chamaId = found(chair);
            UUID chairMembership = UUID.fromString(JsonPath.read(mvc.perform(chair.get("/api/v1/chamas/{id}/members/me", chamaId))
                    .andReturn().getResponse().getContentAsString(), "$.data.membership.membershipId"));
            Account secretary = accounts.verified("Sam");
            UUID secretaryMembership = activeMember(chair, chamaId, secretary);

            // Granular permissions: let secretaries manage roles in this chama.
            mvc.perform(chair.put("/api/v1/chamas/{id}/roles/SECRETARY/permissions",
                    "{\"permissions\":[\"CHAMA_VIEW\",\"MEMBER_VIEW\",\"MEMBER_ROLES_MANAGE\"]}", chamaId)).andExpect(status().isOk());
            mvc.perform(chair.put("/api/v1/chamas/{c}/members/{m}/roles", "{\"roles\":[\"SECRETARY\"]}", chamaId, secretaryMembership))
                    .andExpect(status().isOk());

            mvc.perform(secretary.put("/api/v1/chamas/{c}/members/{m}/roles", "{\"roles\":[\"MEMBER\"]}", chamaId, chairMembership))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("MEMBER_LAST_CHAIRPERSON"));
        }

        @Test
        void suspensionTakesAccessAwayImmediately() throws Exception {
            Account chair = accounts.verified("Kariuki");
            UUID chamaId = found(chair);
            Account member = accounts.verified("Grace");
            UUID membershipId = activeMember(chair, chamaId, member);

            mvc.perform(chair.post("/api/v1/chamas/{c}/members/{m}/suspend", "{\"reason\":\"Missed three meetings\"}",
                            chamaId, membershipId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("SUSPENDED"));
            mvc.perform(member.get("/api/v1/chamas/{id}", chamaId)).andExpect(status().isForbidden());

            mvc.perform(chair.post("/api/v1/chamas/{c}/members/{m}/reactivate", chamaId, membershipId)).andExpect(status().isOk());
            mvc.perform(member.get("/api/v1/chamas/{id}", chamaId)).andExpect(status().isOk());
        }
    }

    @Nested
    class AuditLog {

        @Test
        void recordsEveryActionWithItsActor() throws Exception {
            Account chair = accounts.verified("Atieno");
            UUID chamaId = found(chair);
            Account member = accounts.verified("Peter");
            activeMember(chair, chamaId, member);
            mvc.perform(chair.put("/api/v1/chamas/{id}/settings", standardSettingsJson().replace("\"approvalQuorum\":2", "\"approvalQuorum\":3"), chamaId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.settings.approvalQuorum").value(3));

            String log = mvc.perform(chair.get("/api/v1/chamas/{id}/audit-log", chamaId))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            List<String> actions = JsonPath.read(log, "$.data[*].action");
            assertThat(actions).containsSubsequence(
                    "CHAMA_SETTINGS_CHANGED", "MEMBER_APPROVED", "MEMBER_INVITATION_ACCEPTED", "MEMBER_INVITED");
            assertThat(actions).contains("CHAMA_CREATED", "MEMBER_FOUNDER_ENROLLED");

            List<Integer> before = JsonPath.read(log, "$.data[?(@.action=='CHAMA_SETTINGS_CHANGED')].details.before.approvalQuorum");
            List<Integer> after = JsonPath.read(log, "$.data[?(@.action=='CHAMA_SETTINGS_CHANGED')].details.after.approvalQuorum");
            assertThat(before).containsExactly(2);
            assertThat(after).containsExactly(3);
            List<String> acceptedBy = JsonPath.read(log, "$.data[?(@.action=='MEMBER_INVITATION_ACCEPTED')].actorUserId");
            assertThat(acceptedBy).containsExactly(member.userId().toString());
        }

        @Test
        void cannotBeRewrittenEvenWithDirectDatabaseAccess() throws Exception {
            Account chair = accounts.verified("Langat");
            UUID chamaId = found(chair);

            assertThatThrownBy(() -> jdbc.update("update audit.audit_log set action = 'NOTHING_HAPPENED' where chama_id = ?", chamaId))
                    .hasMessageContaining("append-only");
            assertThatThrownBy(() -> jdbc.update("delete from audit.audit_log where chama_id = ?", chamaId))
                    .hasMessageContaining("append-only");
        }
    }

    // ---- helpers -----------------------------------------------------------------------

    private UUID found(Account founder) throws Exception {
        String body = mvc.perform(founder.post("/api/v1/chamas", "{\"name\":\"Chama of %s\"}".formatted(founder.email())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.data.id"));
    }

    private UUID latestChamaOf(Account account) throws Exception {
        String body = mvc.perform(account.get("/api/v1/me/chamas")).andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(body, "$.data[*].chamaId");
        return UUID.fromString(ids.getLast());
    }

    private UUID invite(Account official, UUID chamaId, String email) throws Exception {
        String body = mvc.perform(official.post("/api/v1/chamas/{id}/members/invitations", "{\"email\":\"%s\"}".formatted(email), chamaId))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.data.membershipId"));
    }

    /** Invite, accept and approve: returns the new member's membership ID. */
    private UUID activeMember(Account chair, UUID chamaId, Account member) throws Exception {
        UUID membershipId = invite(chair, chamaId, member.email());
        expectOk(mvc.perform(member.post("/api/v1/chamas/{c}/members/{m}/accept", chamaId, membershipId)));
        expectOk(mvc.perform(chair.post("/api/v1/chamas/{c}/members/{m}/approve", chamaId, membershipId)));
        return membershipId;
    }

    private static void expectOk(ResultActions result) throws Exception {
        result.andExpect(status().isOk());
    }

    private static String standardSettingsJson() {
        return """
                {"contributionAmountMinor":500000,"contributionFrequency":"MONTHLY","contributionDueDay":5,
                 "lateGraceDays":5,"latePenaltyBasisPoints":200,"loanMaxMultiplier":3,"loanInterestBasisPoints":1000,
                 "loanMinMembershipMonths":3,"loanGuarantorThresholdMinor":5000000,"loanGuarantorsRequired":2,
                 "approvalQuorum":2}""";
    }
}
