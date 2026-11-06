package ke.chama.modules.contribution;

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
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contributions over HTTP. Each chama is set to monthly contributions due on the 1st, and its
 * members' joining dates moved back, so the current period is already due whatever today is.
 */
@IntegrationTest
class ContributionIntegrationTests {

    static final long KES_5000 = 500_000;

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
    class Recording {

        @Test
        void aPaymentPostsToTheMembersSavingsAndSendsAReceipt() throws Exception {
            TestChama chama = dueOnTheFirst("Achieng");
            Account member = owingMember(chama, "Kevin");

            record(chama.chair(), member, KES_5000, "MPESA", "qk7h2xy9zb")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.status").value("RECORDED"))
                    .andExpect(jsonPath("$.data.reference").value("QK7H2XY9ZB"))
                    .andExpect(jsonPath("$.data.memberName").value("Kevin Test"))
                    .andExpect(jsonPath("$.data.ledgerEntryId").isString());

            mvc.perform(member.get("/api/v1/ledger/me"))
                    .andExpect(jsonPath("$.data.savingsMinor").value(KES_5000));
            mvc.perform(chama.chair().get("/api/v1/ledger/accounts"))
                    .andExpect(jsonPath("$.data[?(@.code=='1020')].balanceMinor").value((int) KES_5000));
            mvc.perform(member.get("/api/v1/contributions/me"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.standing.arrearsMinor").value(0))
                    .andExpect(jsonPath("$.data.periods[0].status").value("PAID"))
                    .andExpect(jsonPath("$.data.recentPayments.length()").value(1));

            String receipt = emails.awaitEmail(member.email(), s -> s.startsWith("Contribution received")).body();
            assertThat(receipt).contains("KES 5,000.00", "M-Pesa", "QK7H2XY9ZB", "up to date");
        }

        @Test
        void officialsCannotRecordTheirOwnContribution() throws Exception {
            TestChama chama = dueOnTheFirst("Kamau");

            record(chama.chair(), chama.chair(), KES_5000, "CASH", null)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("CONTRIBUTION_SELF_RECORDING"));
        }

        @Test
        void anMpesaCodeIsRecordedOnlyOnce() throws Exception {
            TestChama chama = dueOnTheFirst("Wanjiru");
            Account member = owingMember(chama, "Faith");
            record(chama.chair(), member, KES_5000, "MPESA", "QK7H2XY9ZC").andExpect(status().isCreated());

            record(chama.chair(), member, KES_5000, "MPESA", "qk7h2xy9zc")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("CONTRIBUTION_DUPLICATE_REFERENCE"));
        }

        @Test
        void mpesaPaymentsNeedTheirCodeAndMembersMustBeSeated() throws Exception {
            TestChama chama = dueOnTheFirst("Otieno");
            Account member = owingMember(chama, "Brian");
            Account applicant = accounts.applicant(chama, "Pending");

            record(chama.chair(), member, KES_5000, "MPESA", null)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("CONTRIBUTION_INVALID_PAYMENT"));
            record(chama.chair(), applicant, KES_5000, "CASH", null)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CONTRIBUTION_MEMBER_NOT_FOUND"));
        }
    }

    @Nested
    class Arrears {

        @Test
        void standingsShowWhoOwesWhat() throws Exception {
            TestChama chama = dueOnTheFirst("Chebet");
            Account paidUp = owingMember(chama, "Grace");
            Account halfPaid = owingMember(chama, "Peter");
            Account ahead = owingMember(chama, "Lucy");
            record(chama.chair(), paidUp, KES_5000, "CASH", null);
            record(chama.chair(), halfPaid, KES_5000 / 2, "CASH", null);
            record(chama.chair(), ahead, 2 * KES_5000, "BANK", "SLIP-0042");

            mvc.perform(chama.chair().get("/api/v1/contributions/standings?inArrears=true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].memberUserId").value(halfPaid.userId().toString()))
                    .andExpect(jsonPath("$.data[0].arrearsMinor").value(KES_5000 / 2))
                    .andExpect(jsonPath("$.data[0].balanceMinor").value(-KES_5000 / 2));

            String all = mvc.perform(chama.chair().get("/api/v1/contributions/standings"))
                    .andReturn().getResponse().getContentAsString();
            List<Integer> advance = JsonPath.read(all, "$.data[?(@.memberUserId=='" + ahead.userId() + "')].advanceMinor");
            assertThat(advance).containsExactly((int) KES_5000);

            String periods = mvc.perform(chama.chair().get("/api/v1/contributions/periods"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].due").value(true))
                    .andExpect(jsonPath("$.data[0].members").value(3))
                    .andExpect(jsonPath("$.data[0].membersPaid").value(2))
                    .andExpect(jsonPath("$.data[0].expectedMinor").value(3 * KES_5000))
                    .andExpect(jsonPath("$.data[0].collectedMinor").value(2 * KES_5000 + KES_5000 / 2))
                    .andReturn().getResponse().getContentAsString();

            String periodId = JsonPath.read(periods, "$.data[0].period.id");
            mvc.perform(chama.chair().get("/api/v1/contributions/periods/{id}", periodId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.members[?(@.memberUserId=='" + halfPaid.userId() + "')].status")
                            .value("PARTIALLY_PAID"));
        }

        @Test
        void membersWhoJoinAfterTheDueDateOweFromTheNextPeriod() throws Exception {
            TestChama chama = dueOnTheFirst("Kiprop");
            Account newcomer = accounts.member(chama, "Newcomer");   // joined today, after the 1st

            mvc.perform(newcomer.get("/api/v1/contributions/me"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.standing.dueToDateMinor").value(0))
                    .andExpect(jsonPath("$.data.periods.length()").value(0));
        }
    }

    @Nested
    class Reversal {

        @Test
        void reversingAPaymentRestoresTheArrearsAndTheLedger() throws Exception {
            TestChama chama = dueOnTheFirst("Atieno");
            Account member = owingMember(chama, "Tom");
            String body = record(chama.chair(), member, KES_5000, "CASH", null).andReturn().getResponse().getContentAsString();
            UUID paymentId = UUID.fromString(JsonPath.read(body, "$.data.id"));

            mvc.perform(chama.chair().post("/api/v1/contributions/{id}/reverse", "{\"reason\":\"Recorded for the wrong member\"}", paymentId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("REVERSED"))
                    .andExpect(jsonPath("$.data.reversalReason").value("Recorded for the wrong member"));

            mvc.perform(member.get("/api/v1/ledger/me")).andExpect(jsonPath("$.data.savingsMinor").value(0));
            mvc.perform(member.get("/api/v1/contributions/me"))
                    .andExpect(jsonPath("$.data.standing.arrearsMinor").value(KES_5000));
            mvc.perform(chama.chair().post("/api/v1/contributions/{id}/reverse", "{\"reason\":\"Again\"}", paymentId))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("CONTRIBUTION_ALREADY_REVERSED"));

            String log = mvc.perform(chama.chair().get("/api/v1/audit-log")).andReturn().getResponse().getContentAsString();
            List<String> actions = JsonPath.read(log, "$.data[*].action");
            // The contribution module audits the reversal; its ledger entry is cancelled through the ledger API.
            assertThat(actions).containsSubsequence("CONTRIBUTION_REVERSED", "CONTRIBUTION_RECORDED");
        }

        @Test
        void aReversedMpesaCodeCanBeRecordedAgain() throws Exception {
            TestChama chama = dueOnTheFirst("Langat");
            Account wrong = owingMember(chama, "Wrong");
            Account right = owingMember(chama, "Right");
            String body = record(chama.chair(), wrong, KES_5000, "MPESA", "QK7H2XY9ZD").andReturn().getResponse().getContentAsString();
            mvc.perform(chama.chair().post("/api/v1/contributions/{id}/reverse", "{\"reason\":\"Wrong member\"}",
                    UUID.fromString(JsonPath.read(body, "$.data.id"))));

            record(chama.chair(), right, KES_5000, "MPESA", "QK7H2XY9ZD").andExpect(status().isCreated());
        }
    }

    @Nested
    class Access {

        @Test
        void membersSeeOnlyTheirOwnContributions() throws Exception {
            TestChama chama = dueOnTheFirst("Mwangi");
            Account member = owingMember(chama, "Sam");
            Account other = owingMember(chama, "Ann");

            record(member, other, KES_5000, "CASH", null).andExpect(status().isForbidden());
            mvc.perform(member.get("/api/v1/contributions")).andExpect(status().isForbidden());
            mvc.perform(member.get("/api/v1/contributions/standings")).andExpect(status().isForbidden());
            mvc.perform(member.get("/api/v1/contributions/me")).andExpect(status().isOk());
        }

        @Test
        void theLicenceMustIncludeContributions() throws Exception {
            TestChama chama = dueOnTheFirst("Rotich");
            Account member = owingMember(chama, "Joy");
            mvc.perform(accounts.platformAdmin().put("/api/v1/platform/chamas/{id}/license",
                    "{\"features\":[\"LOANS\"]}", chama.id())).andExpect(status().isOk());

            record(chama.chair(), member, KES_5000, "CASH", null)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("LICENSE_FEATURE_NOT_INCLUDED"));
        }

        @Test
        void anotherChamasPaymentsAreInvisible() throws Exception {
            TestChama mokica = dueOnTheFirst("Mokica");
            TestChama jamii = dueOnTheFirst("Jamii");
            Account member = owingMember(mokica, "Esther");
            String body = record(mokica.chair(), member, KES_5000, "CASH", null).andReturn().getResponse().getContentAsString();

            mvc.perform(jamii.chair().get("/api/v1/contributions/{id}", (Object) JsonPath.read(body, "$.data.id")))
                    .andExpect(status().isNotFound());
            mvc.perform(jamii.chair().get("/api/v1/contributions"))
                    .andExpect(jsonPath("$.data[*].memberUserId").value(not(hasItem(member.userId().toString()))));
        }
    }

    // ---- helpers -----------------------------------------------------------------------------

    /** A chama whose contributions are due on the 1st, so the current period is always already due. */
    private TestChama dueOnTheFirst(String chair) throws Exception {
        TestChama chama = accounts.onboard(chair);
        mvc.perform(chama.chair().put("/api/v1/chama/settings", """
                        {"contributionAmountMinor":%d,"contributionFrequency":"MONTHLY","contributionDueDay":1,
                         "lateGraceDays":5,"latePenaltyBasisPoints":200,"loanMaxMultiplier":3,"loanInterestBasisPoints":1000,
                         "loanMinMembershipMonths":3,"loanGuarantorThresholdMinor":5000000,"loanGuarantorsRequired":2,
                         "approvalQuorum":2}""".formatted(KES_5000)))
                .andExpect(status().isOk());
        return chama;
    }

    /** An active member who joined two months ago, so they owe the current period. */
    private Account owingMember(TestChama chama, String firstName) throws Exception {
        Account member = accounts.member(chama, firstName);
        owner.jdbc().update("update member.memberships set joined_at = now() - interval '60 days' where user_id = ?",
                member.userId());
        return member;
    }

    private ResultActions record(Account official, Account member, long amount, String method, String reference)
            throws Exception {
        return mvc.perform(official.post("/api/v1/contributions", """
                {"memberUserId":"%s","amountMinor":%d,"method":"%s"%s}"""
                .formatted(member.userId(), amount, method, reference == null ? "" : ",\"reference\":\"" + reference + "\"")));
    }
}
