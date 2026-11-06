package ke.chama;

import com.jayway.jsonpath.JsonPath;
import ke.chama.TestAccounts.Account;
import ke.chama.TestAccounts.TestChama;
import ke.chama.TestcontainersConfiguration.OwnerJdbc;
import ke.chama.shared.tenancy.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Chamas share one database but never each other's data, and every chama runs on its own
 * licence. Checked through the API and, for isolation, directly against PostgreSQL.
 */
@IntegrationTest
class TenancyIntegrationTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    RecordingEmailSender emails;

    /** The application's own JdbcTemplate: restricted role, row-level security applies. */
    @Autowired
    JdbcTemplate appJdbc;

    @Autowired
    OwnerJdbc owner;

    TestAccounts accounts;

    @BeforeEach
    void setUp() {
        accounts = new TestAccounts(mvc, emails);
    }

    @Nested
    class Isolation {

        @Test
        void aChamaSeesOnlyItsOwnMembers() throws Exception {
            TestChama mokica = accounts.onboard("Mokica");
            TestChama jamii = accounts.onboard("Jamii");
            Account jamiiApplicant = accounts.applicant(jamii, "Otieno");
            java.util.UUID foreignMembership = accounts.membershipId(jamiiApplicant);

            mvc.perform(mokica.chair().get("/api/v1/members"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.meta.totalElements").value(1))
                    .andExpect(jsonPath("$.data[*].membershipId").value(not(hasItem(foreignMembership.toString()))));

            // Another chama's membership id is simply not found, never acted upon.
            mvc.perform(mokica.chair().post("/api/v1/members/{id}/approve", foreignMembership))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("MEMBER_NOT_FOUND"));
            mvc.perform(jamiiApplicant.get("/api/v1/members/me"))
                    .andExpect(jsonPath("$.data.membership.status").value("PENDING_APPROVAL"));
        }

        @Test
        void eachChamasAuditLogHoldsOnlyItsOwnEntries() throws Exception {
            TestChama mokica = accounts.onboard("Mokica");
            TestChama jamii = accounts.onboard("Jamii");

            String log = mvc.perform(mokica.chair().get("/api/v1/audit-log?size=100"))
                    .andReturn().getResponse().getContentAsString();
            List<String> targets = JsonPath.read(log, "$.data[?(@.action=='CHAMA_CREATED')].targetId");
            assertThat(targets).containsExactly(mokica.id().toString()).doesNotContain(jamii.id().toString());
        }

        @Test
        void rowLevelSecurityHidesOtherChamasEvenFromRawSql() throws Exception {
            TestChama mokica = accounts.onboard("Mokica");
            TestChama jamii = accounts.onboard("Jamii");
            String countUsers = "select count(*) from auth.users where tenant_id = ?";

            // The application role, scoped to Mokica, cannot see Jamii's rows even when it asks for them.
            assertThat(TenantContext.callAs(mokica.id(), mokica.code(),
                    () -> appJdbc.queryForObject(countUsers, Long.class, jamii.id()))).isZero();
            assertThat(TenantContext.callAs(mokica.id(), mokica.code(),
                    () -> appJdbc.queryForObject(countUsers, Long.class, mokica.id()))).isEqualTo(1);
            // With no tenant at all, nothing is visible: isolation fails closed.
            assertThat(appJdbc.queryForObject("select count(*) from auth.users", Long.class)).isZero();
            // The owner account confirms both chamas' rows exist.
            assertThat(owner.jdbc().queryForObject(countUsers, Long.class, jamii.id())).isEqualTo(1);
        }

        @Test
        void rowLevelSecurityRefusesWritesIntoAnotherChama() throws Exception {
            TestChama mokica = accounts.onboard("Mokica");
            TestChama jamii = accounts.onboard("Jamii");

            assertThatThrownBy(() -> TenantContext.runAs(mokica.id(), mokica.code(), () -> appJdbc.update(
                    "insert into member.role_permissions (tenant_id, role, permission) values (?, 'MEMBER', 'LOAN_APPROVE')",
                    jamii.id())))
                    .rootCause().hasMessageContaining("row-level security");
            assertThat(TenantContext.callAs(mokica.id(), mokica.code(), () -> appJdbc.update(
                    "update member.role_permissions set permission = 'AUDIT_LOG_VIEW' where tenant_id = ?", jamii.id())))
                    .as("rows of another tenant are invisible to UPDATE").isZero();
        }

        @Test
        void theApplicationRoleCannotChangeTheAuditLog() throws Exception {
            TestChama mokica = accounts.onboard("Mokica");

            assertThatThrownBy(() -> TenantContext.runAs(mokica.id(), mokica.code(), () -> appJdbc.update(
                    "update audit.audit_log set action = 'NOTHING' where tenant_id = ?", mokica.id())))
                    .rootCause().hasMessageContaining("permission denied");
        }
    }

    @Nested
    class Licensing {

        @Test
        void anExpiredLicenceBlocksTheChamaUntilRenewed() throws Exception {
            TestChama chama = accounts.onboard("Kerubo");
            expireLicence(chama);
            Account chair = chama.chair();

            mvc.perform(chair.get("/api/v1/members"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("LICENSE_EXPIRED"));
            // Members can still see their chama and why it is blocked.
            mvc.perform(chair.get("/api/v1/chama")).andExpect(status().isOk());
            mvc.perform(chair.get("/api/v1/chama/license"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.state").value("EXPIRED"))
                    .andExpect(jsonPath("$.data.daysRemaining").value(0));

            Instant nextYear = Instant.now().plus(365, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
            mvc.perform(accounts.platformAdmin().put("/api/v1/platform/chamas/{id}/license",
                            "{\"plan\":\"STANDARD\",\"validUntil\":\"%s\"}".formatted(nextYear), chama.id()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.state").value("ACTIVE"))
                    .andExpect(jsonPath("$.data.plan").value("STANDARD"))
                    .andExpect(jsonPath("$.data.maxMembers").value(100));

            mvc.perform(chair.get("/api/v1/members")).andExpect(status().isOk());
        }

        @Test
        void aSuspendedLicenceBlocksTheChamaAndTheChangeIsAudited() throws Exception {
            TestChama chama = accounts.onboard("Kimani");
            Account admin = accounts.platformAdmin();

            mvc.perform(admin.put("/api/v1/platform/chamas/{id}/license",
                            "{\"status\":\"SUSPENDED\",\"notes\":\"Invoice unpaid\"}", chama.id()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.state").value("SUSPENDED"));
            mvc.perform(chama.chair().get("/api/v1/audit-log"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("LICENSE_SUSPENDED"));

            mvc.perform(admin.put("/api/v1/platform/chamas/{id}/license", "{\"status\":\"ACTIVE\"}", chama.id()))
                    .andExpect(status().isOk());
            String log = mvc.perform(chama.chair().get("/api/v1/audit-log"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            List<String> actors = JsonPath.read(log, "$.data[?(@.action=='LICENSE_UPDATED')].actorUserId");
            assertThat(actors).hasSize(2).containsOnly(admin.userId().toString());
        }

        @Test
        void theLicenceCapsTheNumberOfMembers() throws Exception {
            TestChama chama = accounts.onboard("Rotich");
            mvc.perform(accounts.platformAdmin().put("/api/v1/platform/chamas/{id}/license", "{\"maxMembers\":2}", chama.id()))
                    .andExpect(status().isOk());

            accounts.member(chama, "First");
            Account third = accounts.applicant(chama, "Third");

            mvc.perform(chama.chair().post("/api/v1/members/{id}/approve", accounts.membershipId(third)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("LICENSE_MEMBER_LIMIT_REACHED"));
        }

        private void expireLicence(TestChama chama) {
            owner.jdbc().update("""
                    update chama.licenses set valid_from = now() - interval '31 days', valid_until = now() - interval '1 day'
                     where tenant_id = ?""", chama.id());
        }
    }

    @Nested
    class PlatformAdministration {

        @Test
        void platformAdminsSeeEveryChama() throws Exception {
            TestChama mokica = accounts.onboard("Mokica");
            TestChama jamii = accounts.onboard("Jamii");

            mvc.perform(accounts.platformAdmin().get("/api/v1/platform/chamas?size=100"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].chama.code").value(hasItem(mokica.code())))
                    .andExpect(jsonPath("$.data[*].chama.code").value(hasItem(jamii.code())))
                    .andExpect(jsonPath("$.data[0].license.licenseKey").isString());
        }

        @Test
        void chamaOfficialsCannotUsePlatformEndpoints() throws Exception {
            TestChama chama = accounts.onboard("Mokica");

            mvc.perform(chama.chair().get("/api/v1/platform/chamas"))
                    .andExpect(status().isForbidden());
            mvc.perform(chama.chair().put("/api/v1/platform/chamas/{id}/license", "{\"maxMembers\":5000}", chama.id()))
                    .andExpect(status().isForbidden());
        }

        @Test
        void platformAdminsHaveNoPlaceInsideAChama() throws Exception {
            mvc.perform(accounts.platformAdmin().get("/api/v1/members"))
                    .andExpect(status().isForbidden());
        }
    }
}
