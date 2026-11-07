package ke.chama.modules.member;

import com.jayway.jsonpath.JsonPath;
import ke.chama.IntegrationTest;
import ke.chama.RecordingEmailSender;
import ke.chama.TestAccounts;
import ke.chama.TestAccounts.Account;
import ke.chama.TestAccounts.TestChama;
import ke.chama.TestcontainersConfiguration.OwnerJdbc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Onboarding a chama, the membership lifecycle, per-chama permissions and the audit log, over HTTP. */
@IntegrationTest
class ChamaMembershipIntegrationTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    RecordingEmailSender emails;

    @Autowired
    OwnerJdbc owner;

    TestAccounts accounts;

    @BeforeEach
    void setUp() {
        accounts = new TestAccounts(mvc, emails);
    }

    @Nested
    class Onboarding {

        @Test
        void platformAdminsCreateTheChamaItsTrialLicenceAndItsChairperson() throws Exception {
            String code = TestAccounts.uniqueCode("umoja");
            String email = TestAccounts.uniqueEmail("achieng");

            onboard(code.toUpperCase(), email, "  Umoja Women Group ", null)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.chama.name").value("Umoja Women Group"))
                    .andExpect(jsonPath("$.data.chama.code").value(code))
                    .andExpect(jsonPath("$.data.chama.currency").value("KES"))
                    .andExpect(jsonPath("$.data.chama.settings.contributionAmountMinor").value(500000))
                    .andExpect(jsonPath("$.data.license.plan").value("TRIAL"))
                    .andExpect(jsonPath("$.data.license.state").value("ACTIVE"))
                    .andExpect(jsonPath("$.data.license.licenseKey").value(org.hamcrest.Matchers.startsWith("CHM-")))
                    .andExpect(jsonPath("$.data.admin.email").value(email));

            String verify = emails.awaitEmail(email, s -> s.contains("Verify")).body();
            assertThat(verify).contains("chama=" + code);

            Account chair = accounts.login(code, email);
            mvc.perform(chair.get("/api/v1/members/me"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.membership.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.data.membership.memberNumber").value(1))
                    .andExpect(jsonPath("$.data.membership.roles").value(hasItems("MEMBER", "CHAIRPERSON")))
                    .andExpect(jsonPath("$.data.permissions").value(hasItems("ROLE_PERMISSIONS_MANAGE", "AUDIT_LOG_VIEW")));
            mvc.perform(chair.get("/api/v1/chama/license"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.daysRemaining").value(29));
        }

        @Test
        void theStartingLicenceCanBeSetInsteadOfTheTrial() throws Exception {
            onboard(TestAccounts.uniqueCode("jamii"), TestAccounts.uniqueEmail("kamau"), "Jamii Chama",
                    "{\"plan\":\"STANDARD\",\"validUntil\":\"2030-01-01T00:00:00Z\",\"maxMembers\":60}")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.license.plan").value("STANDARD"))
                    .andExpect(jsonPath("$.data.license.validUntil").value("2030-01-01T00:00:00Z"))
                    .andExpect(jsonPath("$.data.license.maxMembers").value(60))
                    .andExpect(jsonPath("$.data.license.features").value(hasItem("LOANS")));
        }

        @Test
        void onlyPlatformAdminsCanAddChamas() throws Exception {
            String body = onboardingJson(TestAccounts.uniqueCode("x"), TestAccounts.uniqueEmail("x"), "X", null);
            mvc.perform(post("/api/v1/platform/chamas").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized());
            mvc.perform(accounts.onboard("Wanjiku").chair().post("/api/v1/platform/chamas", body))
                    .andExpect(status().isForbidden());
            // The old public route is gone.
            mvc.perform(post("/api/v1/chamas").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void chamaCodesAreUnique() throws Exception {
            TestChama existing = accounts.onboard("Kamau");

            onboard(existing.code().toUpperCase(), TestAccounts.uniqueEmail("a"), "Some Chama", null)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("CHAMA_CODE_TAKEN"));
        }

        @Test
        void reservedAndMalformedCodesAreRejected() throws Exception {
            onboard("platform", TestAccounts.uniqueEmail("a"), "Some Chama", null)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("CHAMA_INVALID_CODE"));
            onboard("no spaces", TestAccounts.uniqueEmail("a"), "Some Chama", null)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.fields[0].field").value("chama.code"));
        }

        @Test
        void rejectsInvalidSettings() throws Exception {
            mvc.perform(accounts.platformAdmin().post("/api/v1/platform/chamas", """
                            {"chama":{"name":"Bad Rules","code":"%s",
                             "settings":{"contributionAmountMinor":500000,"contributionFrequency":"MONTHLY",
                             "contributionDueDay":31,"lateGraceDays":5,"latePenaltyBasisPoints":200,"loanMaxMultiplier":3,
                             "loanInterestBasisPoints":1000,"loanMinMembershipMonths":3,"loanGuarantorThresholdMinor":5000000,
                             "loanGuarantorsRequired":2,"approvalQuorum":2},
                             "admin":{"email":"%s","phoneNumber":"0712345678","password":"%s","firstName":"A","lastName":"B"}}}"""
                            .formatted(TestAccounts.uniqueCode("bad"), TestAccounts.uniqueEmail("a"), TestAccounts.PASSWORD)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("CHAMA_INVALID_SETTINGS"));
        }

        private org.springframework.test.web.servlet.ResultActions onboard(String code, String email, String name,
                                                                          String license) throws Exception {
            return mvc.perform(accounts.platformAdmin().post("/api/v1/platform/chamas",
                    onboardingJson(code, email, name, license)));
        }

        private static String onboardingJson(String code, String email, String name, String license) {
            return """
                    {"chama":{"name":"%s","code":"%s",
                     "admin":{"email":"%s","phoneNumber":"0712345678","password":"%s",
                              "firstName":"Achieng","lastName":"Odhiambo"}}%s}"""
                    .formatted(name, code, email, TestAccounts.PASSWORD, license == null ? "" : ",\"license\":" + license);
        }
    }

    @Nested
    class MembershipLifecycle {

        @Test
        void registerWithTheChamaCodeThenGetApproved() throws Exception {
            TestChama chama = accounts.onboard("Kamau");
            Account jane = accounts.applicant(chama, "Jane");

            mvc.perform(jane.get("/api/v1/members/me"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.membership.status").value("PENDING_APPROVAL"))
                    .andExpect(jsonPath("$.data.permissions").isEmpty());
            mvc.perform(jane.get("/api/v1/chama")).andExpect(status().isForbidden());

            java.util.UUID membershipId = accounts.membershipId(jane);
            mvc.perform(chama.chair().get("/api/v1/members?status=PENDING_APPROVAL"))
                    .andExpect(jsonPath("$.data[*].membershipId").value(hasItem(membershipId.toString())));

            mvc.perform(chama.chair().post("/api/v1/members/{id}/approve", membershipId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.data.memberNumber").value(2))
                    .andExpect(jsonPath("$.data.firstName").value("Jane"));

            assertThat(emails.awaitEmail(jane.email(), s -> s.startsWith("Welcome")).body()).contains("member number is 2");
            mvc.perform(jane.get("/api/v1/chama")).andExpect(status().isOk());
        }

        @Test
        void invitationsEmailTheChamaCode() throws Exception {
            TestChama chama = accounts.onboard("Mwangi");
            String email = TestAccounts.uniqueEmail("brian");

            mvc.perform(chama.chair().post("/api/v1/members/invitations", "{\"email\":\"%s\"}".formatted(email.toUpperCase())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.email").value(email))
                    .andExpect(jsonPath("$.data.chamaCode").value(chama.code()));

            String body = emails.awaitEmail(email, s -> s.startsWith("You're invited")).body();
            assertThat(body).contains("Mwangi Test", "/join?chama=" + chama.code());
        }

        @Test
        void cannotInviteSomeoneWhoAlreadyHasAnAccountHere() throws Exception {
            TestChama chama = accounts.onboard("Wekesa");
            Account member = accounts.member(chama, "Faith");

            mvc.perform(chama.chair().post("/api/v1/members/invitations", "{\"email\":\"%s\"}".formatted(member.email())))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("MEMBER_ALREADY_EXISTS"));
        }

        @Test
        void rejectedApplicantsStayWithoutAccess() throws Exception {
            TestChama chama = accounts.onboard("Njoroge");
            Account applicant = accounts.applicant(chama, "Mallory");

            mvc.perform(chama.chair().post("/api/v1/members/{id}/reject", accounts.membershipId(applicant)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("REJECTED"));
            mvc.perform(applicant.get("/api/v1/members")).andExpect(status().isForbidden());
        }
    }

    @Nested
    class Permissions {

        @Test
        void plainMembersCanReadButNotManage() throws Exception {
            TestChama chama = accounts.onboard("Chebet");
            Account member = accounts.member(chama, "Kevin");

            mvc.perform(member.get("/api/v1/chama")).andExpect(status().isOk());
            mvc.perform(member.get("/api/v1/members")).andExpect(status().isOk());
            mvc.perform(member.put("/api/v1/chama/settings", standardSettingsJson()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
            mvc.perform(member.post("/api/v1/members/invitations", "{\"email\":\"x@example.com\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(member.get("/api/v1/audit-log")).andExpect(status().isForbidden());
        }

        @Test
        void treasurerGetsMoneyPermissionsButNotTheAuditLog() throws Exception {
            TestChama chama = accounts.onboard("Auma");
            Account treasurer = accounts.member(chama, "Tom");

            mvc.perform(chama.chair().put("/api/v1/members/{m}/roles", "{\"roles\":[\"TREASURER\"]}",
                            accounts.membershipId(treasurer)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.roles").value(hasItems("MEMBER", "TREASURER")));

            mvc.perform(treasurer.get("/api/v1/members/me"))
                    .andExpect(jsonPath("$.data.permissions").value(hasItems("CONTRIBUTION_RECORD", "LOAN_DISBURSE")))
                    .andExpect(jsonPath("$.data.permissions").value(not(hasItem("EXPENSE_APPROVE"))));
            mvc.perform(treasurer.get("/api/v1/audit-log")).andExpect(status().isForbidden());
        }

        @Test
        void customRolePermissionsTakeEffectOnTheNextRequest() throws Exception {
            TestChama chama = accounts.onboard("Nduta");
            Account member = accounts.member(chama, "Lucy");
            mvc.perform(member.get("/api/v1/audit-log")).andExpect(status().isForbidden());

            mvc.perform(chama.chair().put("/api/v1/roles/MEMBER/permissions",
                            "{\"permissions\":[\"CHAMA_VIEW\",\"MEMBER_VIEW\",\"LOAN_APPLY\",\"AUDIT_LOG_VIEW\"]}"))
                    .andExpect(status().isOk());

            // Same access token as before: permissions come from the database, not the JWT.
            mvc.perform(member.get("/api/v1/audit-log")).andExpect(status().isOk());
        }

        @Test
        void chairpersonRoleCannotLoseRoleManagement() throws Exception {
            TestChama chama = accounts.onboard("Mutua");

            mvc.perform(chama.chair().put("/api/v1/roles/CHAIRPERSON/permissions",
                            "{\"permissions\":[\"CHAMA_VIEW\",\"MEMBER_ROLES_MANAGE\"]}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("ROLE_PERMISSIONS_LOCKOUT"));
        }
    }

    @Nested
    class SeparationOfDuties {

        @Test
        void nobodyChangesTheirOwnRoles() throws Exception {
            TestChama chama = accounts.onboard("Omondi");

            mvc.perform(chama.chair().put("/api/v1/members/{m}/roles", "{\"roles\":[\"MEMBER\"]}",
                            accounts.membershipId(chama.chair())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("MEMBER_SELF_MANAGEMENT"));
        }

        @Test
        void theLastChairpersonCannotBeRemoved() throws Exception {
            TestChama chama = accounts.onboard("Wairimu");
            Account chair = chama.chair();
            Account secretary = accounts.member(chama, "Sam");

            // Granular permissions: let secretaries manage roles in this chama.
            mvc.perform(chair.put("/api/v1/roles/SECRETARY/permissions",
                    "{\"permissions\":[\"CHAMA_VIEW\",\"MEMBER_VIEW\",\"MEMBER_ROLES_MANAGE\"]}")).andExpect(status().isOk());
            mvc.perform(chair.put("/api/v1/members/{m}/roles", "{\"roles\":[\"SECRETARY\"]}", accounts.membershipId(secretary)))
                    .andExpect(status().isOk());

            mvc.perform(secretary.put("/api/v1/members/{m}/roles", "{\"roles\":[\"MEMBER\"]}", accounts.membershipId(chair)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("MEMBER_LAST_CHAIRPERSON"));
        }

        @Test
        void suspensionTakesAccessAwayImmediately() throws Exception {
            TestChama chama = accounts.onboard("Kariuki");
            Account member = accounts.member(chama, "Grace");
            java.util.UUID membershipId = accounts.membershipId(member);

            mvc.perform(chama.chair().post("/api/v1/members/{m}/suspend", "{\"reason\":\"Missed three meetings\"}", membershipId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("SUSPENDED"));
            mvc.perform(member.get("/api/v1/chama")).andExpect(status().isForbidden());

            mvc.perform(chama.chair().post("/api/v1/members/{m}/reactivate", membershipId)).andExpect(status().isOk());
            mvc.perform(member.get("/api/v1/chama")).andExpect(status().isOk());
        }
    }

    @Nested
    class AuditLog {

        @Test
        void recordsEveryActionWithItsActor() throws Exception {
            TestChama chama = accounts.onboard("Atieno");
            Account member = accounts.member(chama, "Peter");
            mvc.perform(chama.chair().put("/api/v1/chama/settings",
                            standardSettingsJson().replace("\"approvalQuorum\":2", "\"approvalQuorum\":3")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.settings.approvalQuorum").value(3));

            String log = mvc.perform(chama.chair().get("/api/v1/audit-log"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            List<String> actions = JsonPath.read(log, "$.data[*].action");
            assertThat(actions).containsSubsequence("CHAMA_SETTINGS_CHANGED", "MEMBER_APPROVED", "MEMBER_APPLIED");
            assertThat(actions).contains("CHAMA_CREATED", "LICENSE_ISSUED", "MEMBER_FOUNDER_ENROLLED");

            List<Integer> before = JsonPath.read(log, "$.data[?(@.action=='CHAMA_SETTINGS_CHANGED')].details.before.approvalQuorum");
            List<Integer> after = JsonPath.read(log, "$.data[?(@.action=='CHAMA_SETTINGS_CHANGED')].details.after.approvalQuorum");
            assertThat(before).containsExactly(2);
            assertThat(after).containsExactly(3);
            List<String> appliedBy = JsonPath.read(log, "$.data[?(@.action=='MEMBER_APPLIED')].actorUserId");
            assertThat(appliedBy).containsExactly(member.userId().toString());
        }

        @Test
        void cannotBeRewrittenEvenWithDirectDatabaseAccess() throws Exception {
            TestChama chama = accounts.onboard("Langat");

            // Even the owner account, which bypasses row-level security, is stopped by the triggers.
            assertThatThrownBy(() -> owner.jdbc().update(
                    "update audit.audit_log set action = 'NOTHING_HAPPENED' where tenant_id = ?", chama.id()))
                    .hasMessageContaining("append-only");
            assertThatThrownBy(() -> owner.jdbc().update("delete from audit.audit_log where tenant_id = ?", chama.id()))
                    .hasMessageContaining("append-only");
        }
    }

    private static String standardSettingsJson() {
        return """
                {"contributionAmountMinor":500000,"contributionFrequency":"MONTHLY","contributionDueDay":5,
                 "lateGraceDays":5,"latePenaltyBasisPoints":200,"loanMaxMultiplier":3,"loanInterestBasisPoints":1000,
                 "loanMinMembershipMonths":3,"loanGuarantorThresholdMinor":5000000,"loanGuarantorsRequired":2,
                 "approvalQuorum":2}""";
    }
}
