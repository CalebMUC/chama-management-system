package ke.chama.modules.penalty;

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
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Penalties over HTTP. Each chama has last month's contribution period (due on the 1st, so long
 * past its grace days) and its members joined three months ago, so last month is always late
 * whatever today is; the current month is due on the 28th and never late yet.
 */
@IntegrationTest
public class PenaltyIntegrationTests {

    static final long KES_5000 = 500_000;
    static final long PENALTY = 10_000;   // 2% of KES 5,000

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
    class LateContributions {

        @Test
        void aLateContributionIsPenalisedOnceAndPostedToTheLedger() throws Exception {
            TestChama chama = chamaWithALateMonth("Achieng");
            Account member = owingMember(chama, "Kevin");

            mvc.perform(member.get("/api/v1/penalties/me"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.outstandingMinor").value(PENALTY))
                    .andExpect(jsonPath("$.data.penalties.length()").value(1))
                    .andExpect(jsonPath("$.data.penalties[0].kind").value("LATE_CONTRIBUTION"))
                    .andExpect(jsonPath("$.data.penalties[0].description").value(org.hamcrest.Matchers
                            .containsString("2% of KES 5,000.00 unpaid")));

            // Assessing again finds nothing new.
            mvc.perform(chama.chair().post("/api/v1/penalties/assess"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("No new penalties"));
            mvc.perform(member.get("/api/v1/penalties/me")).andExpect(jsonPath("$.data.penalties.length()").value(1));

            mvc.perform(member.get("/api/v1/ledger/me")).andExpect(jsonPath("$.data.penaltiesOutstandingMinor").value(PENALTY));
            mvc.perform(chama.chair().get("/api/v1/ledger/accounts"))
                    .andExpect(jsonPath("$.data[?(@.code=='4100')].balanceMinor").value((int) PENALTY))
                    .andExpect(jsonPath("$.data[?(@.code=='1110')].balanceMinor").value((int) PENALTY));

            assertThat(emails.awaitEmail(member.email(), s -> s.startsWith("Penalty of KES 100.00")).body())
                    .contains("late-contribution penalty");
        }

        @Test
        void onlyTheUnpaidPartIsPenalised() throws Exception {
            TestChama chama = chamaWithALateMonth("Chebet");
            Account member = owingMember(chama, "Grace");
            payContribution(chama, member, KES_5000 / 2);

            mvc.perform(member.get("/api/v1/penalties/me"))
                    .andExpect(jsonPath("$.data.penalties[0].amountMinor").value(PENALTY / 2));
        }

        @Test
        void membersWhoPaidAreNotPenalised() throws Exception {
            TestChama chama = chamaWithALateMonth("Otieno");
            Account member = owingMember(chama, "Brian");
            payContribution(chama, member, KES_5000);

            mvc.perform(member.get("/api/v1/penalties/me"))
                    .andExpect(jsonPath("$.data.penalties.length()").value(0))
                    .andExpect(jsonPath("$.data.outstandingMinor").value(0));
        }
    }

    @Nested
    class PayingAndWaiving {

        @Test
        void aPenaltyIsPaidInPartsAndThenClosed() throws Exception {
            TestChama chama = chamaWithALateMonth("Wanjiru");
            Account member = owingMember(chama, "Faith");
            UUID penaltyId = onlyPenaltyOf(member);

            pay(chama.chair(), penaltyId, 4_000, "CASH", null)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.status").value("OUTSTANDING"))
                    .andExpect(jsonPath("$.data.outstandingMinor").value(6_000));
            pay(chama.chair(), penaltyId, 6_001, "CASH", null)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("PENALTY_INVALID"));
            pay(chama.chair(), penaltyId, 6_000, "MPESA", "QP1PENALTY")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.status").value("PAID"));
            pay(chama.chair(), penaltyId, 1, "CASH", null)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("PENALTY_CLOSED"));

            mvc.perform(chama.chair().get("/api/v1/penalties/{id}", penaltyId))
                    .andExpect(jsonPath("$.data.payments.length()").value(2));
            mvc.perform(member.get("/api/v1/ledger/me")).andExpect(jsonPath("$.data.penaltiesOutstandingMinor").value(0));
        }

        @Test
        void theChairpersonWaivesButTheTreasurerCannot() throws Exception {
            TestChama chama = chamaWithALateMonth("Kamau");
            Account member = owingMember(chama, "Peter");
            Account treasurer = accounts.member(chama, "Tom");
            mvc.perform(chama.chair().put("/api/v1/members/{m}/roles", "{\"roles\":[\"TREASURER\"]}", accounts.membershipId(treasurer)));
            UUID penaltyId = onlyPenaltyOf(member);

            mvc.perform(treasurer.post("/api/v1/penalties/{id}/waive", "{\"reason\":\"Friend\"}", penaltyId))
                    .andExpect(status().isForbidden());
            mvc.perform(chama.chair().post("/api/v1/penalties/{id}/waive", "{\"reason\":\"First offence\"}", penaltyId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("WAIVED"))
                    .andExpect(jsonPath("$.data.waivedMinor").value(PENALTY));

            mvc.perform(chama.chair().get("/api/v1/ledger/accounts"))
                    .andExpect(jsonPath("$.data[?(@.code=='4100')].balanceMinor").value(0))
                    .andExpect(jsonPath("$.data[?(@.code=='1110')].balanceMinor").value(0));
            String log = mvc.perform(chama.chair().get("/api/v1/audit-log")).andReturn().getResponse().getContentAsString();
            List<String> reasons = JsonPath.read(log, "$.data[?(@.action=='PENALTY_WAIVED')].details.reason");
            assertThat(reasons).containsExactly("First offence");
        }

        @Test
        void nobodyHandlesTheirOwnPenalty() throws Exception {
            TestChama chama = chamaWithALateMonth("Atieno");
            Account chair = chama.chair();

            mvc.perform(chair.post("/api/v1/penalties/fines",
                            "{\"memberUserId\":\"%s\",\"amountMinor\":50000,\"reason\":\"Late to meeting\"}".formatted(chair.userId())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("PENALTY_SELF_HANDLING"));
        }
    }

    @Nested
    class Fines {

        @Test
        void officialsFineMembersForOtherReasons() throws Exception {
            TestChama chama = chamaWithALateMonth("Njoroge");
            Account member = accounts.member(chama, "Lucy");

            mvc.perform(chama.chair().post("/api/v1/penalties/fines",
                            "{\"memberUserId\":\"%s\",\"amountMinor\":50000,\"reason\":\"Missed the November meeting\"}"
                                    .formatted(member.userId())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.kind").value("FINE"))
                    .andExpect(jsonPath("$.data.amountMinor").value(50_000))
                    .andExpect(jsonPath("$.data.assessedBy").value(chama.chair().userId().toString()));

            mvc.perform(chama.chair().get("/api/v1/penalties?memberUserId={m}", member.userId()))
                    .andExpect(jsonPath("$.data[*].description").value(org.hamcrest.Matchers.hasItem("Missed the November meeting")));
        }
    }

    @Nested
    class Access {

        @Test
        void membersSeeOnlyTheirOwnPenalties() throws Exception {
            TestChama chama = chamaWithALateMonth("Mwangi");
            Account member = owingMember(chama, "Sam");

            mvc.perform(member.get("/api/v1/penalties")).andExpect(status().isForbidden());
            mvc.perform(member.post("/api/v1/penalties/assess")).andExpect(status().isForbidden());
            mvc.perform(member.get("/api/v1/penalties/me")).andExpect(status().isOk());
        }

        @Test
        void theLicenceMustIncludePenalties() throws Exception {
            TestChama chama = chamaWithALateMonth("Rotich");
            mvc.perform(accounts.platformAdmin().put("/api/v1/platform/chamas/{id}/license",
                    "{\"features\":[\"CONTRIBUTIONS\"]}", chama.id())).andExpect(status().isOk());

            mvc.perform(chama.chair().get("/api/v1/penalties"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("LICENSE_FEATURE_NOT_INCLUDED"));
        }
    }

    // ---- helpers -----------------------------------------------------------------------------

    /** Monthly contributions of KES 5,000 due on the 28th, plus last month's period, due on the 1st. */
    TestChama chamaWithALateMonth(String chair) throws Exception {
        TestChama chama = accounts.onboard(chair);
        mvc.perform(chama.chair().put("/api/v1/chama/settings", """
                        {"contributionAmountMinor":%d,"contributionFrequency":"MONTHLY","contributionDueDay":28,
                         "lateGraceDays":5,"latePenaltyBasisPoints":200,"loanMaxMultiplier":3,"loanInterestBasisPoints":1000,
                         "loanMinMembershipMonths":3,"loanGuarantorThresholdMinor":5000000,"loanGuarantorsRequired":2,
                         "approvalQuorum":2}""".formatted(KES_5000)))
                .andExpect(status().isOk());
        owner.jdbc().update("""
                insert into contribution.periods (id, tenant_id, period_key, frequency, start_date, end_date, due_date,
                                                  amount_due_minor, currency, opened_at)
                select gen_random_uuid(), ?, to_char(m, 'YYYY-MM'), 'MONTHLY', m, (m + interval '1 month - 1 day')::date, m,
                       ?, 'KES', now()
                  from (select (date_trunc('month', timezone('Africa/Nairobi', now())) - interval '1 month')::date as m) last_month""",
                chama.id(), KES_5000);
        return chama;
    }

    /** An active member who joined three months ago: they owe last month and this month. */
    Account owingMember(TestChama chama, String firstName) throws Exception {
        Account member = accounts.member(chama, firstName);
        owner.jdbc().update("update member.memberships set joined_at = now() - interval '90 days' where user_id = ?",
                member.userId());
        return member;
    }

    private void payContribution(TestChama chama, Account member, long amount) throws Exception {
        mvc.perform(chama.chair().post("/api/v1/contributions",
                        "{\"memberUserId\":\"%s\",\"amountMinor\":%d,\"method\":\"CASH\"}".formatted(member.userId(), amount)))
                .andExpect(status().isCreated());
    }

    private UUID onlyPenaltyOf(Account member) throws Exception {
        String body = mvc.perform(member.get("/api/v1/penalties/me")).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.data.penalties[0].id"));
    }

    private org.springframework.test.web.servlet.ResultActions pay(Account official, UUID penaltyId, long amount,
                                                                   String method, String reference) throws Exception {
        return mvc.perform(official.post("/api/v1/penalties/{id}/payments", """
                {"amountMinor":%d,"method":"%s"%s}""".formatted(amount, method,
                reference == null ? "" : ",\"reference\":\"" + reference + "\""), penaltyId));
    }
}
