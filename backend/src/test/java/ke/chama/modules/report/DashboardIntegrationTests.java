package ke.chama.modules.report;

import ke.chama.IntegrationTest;
import ke.chama.RecordingEmailSender;
import ke.chama.TestAccounts;
import ke.chama.TestAccounts.Account;
import ke.chama.TestAccounts.TestChama;
import ke.chama.TestcontainersConfiguration.OwnerJdbc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The chama and member dashboards over HTTP. */
@IntegrationTest
class DashboardIntegrationTests {

    static final long KES_5000 = 500_000;
    static final long FINE = 50_000;

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

    @Test
    void officialsSeeTheChamasKeyFigures() throws Exception {
        TestChama chama = chamaWithActivity("Achieng", "Kevin").chama();

        mvc.perform(chama.chair().get("/api/v1/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.features").value(hasItem("LOANS")))
                .andExpect(jsonPath("$.data.members.active").value(2))
                .andExpect(jsonPath("$.data.cash.mobileMoneyMinor").value(KES_5000))
                .andExpect(jsonPath("$.data.cash.totalMinor").value(KES_5000))
                .andExpect(jsonPath("$.data.savings.totalMinor").value(KES_5000))
                .andExpect(jsonPath("$.data.savings.products[0].code").value("DEP"))
                .andExpect(jsonPath("$.data.contributions.currentPeriod").isString())
                .andExpect(jsonPath("$.data.contributions.collectedMinor").value(KES_5000))
                .andExpect(jsonPath("$.data.loans.activeLoans").value(0))
                .andExpect(jsonPath("$.data.loans.awaitingApproval").value(1))
                .andExpect(jsonPath("$.data.penaltiesOutstandingMinor").value(FINE))
                .andExpect(jsonPath("$.data.investments.active").value(0))
                .andExpect(jsonPath("$.data.thisMonth.incomeMinor").value(FINE))
                .andExpect(jsonPath("$.data.yearToDate.surplusMinor").value(FINE))
                .andExpect(jsonPath("$.data.pending.loansAwaitingApproval").value(1))
                .andExpect(jsonPath("$.data.pending.expensesAwaitingApproval").value(0))
                .andExpect(jsonPath("$.data.recentEntries.length()").value(2))
                .andExpect(jsonPath("$.data.recentEntries[0].sourceType").value("PENALTY"));
    }

    @Test
    void aMemberSeesTheirOwnPosition() throws Exception {
        Activity activity = chamaWithActivity("Chebet", "Grace");

        mvc.perform(activity.member().get("/api/v1/dashboard/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberName").value("Grace Test"))
                .andExpect(jsonPath("$.data.savings.totalMinor").value(KES_5000))
                .andExpect(jsonPath("$.data.savings.accounts[0].productCode").value("DEP"))
                .andExpect(jsonPath("$.data.contributions.totalPaidMinor").value(KES_5000))
                .andExpect(jsonPath("$.data.loans.inProgress").value(1))
                .andExpect(jsonPath("$.data.loans.outstandingMinor").value(0))
                .andExpect(jsonPath("$.data.penaltiesOutstandingMinor").value(FINE));
        mvc.perform(activity.member().get("/api/v1/dashboard")).andExpect(status().isForbidden());
    }

    @Test
    void sectionsTheLicenceDoesNotIncludeAreLeftOut() throws Exception {
        TestChama chama = accounts.onboard("Wekesa");
        mvc.perform(accounts.platformAdmin().put("/api/v1/platform/chamas/{id}/license",
                        "{\"features\":[\"CONTRIBUTIONS\",\"REPORTS\"]}", chama.id()))
                .andExpect(status().isOk());

        mvc.perform(chama.chair().get("/api/v1/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.features").value(not(hasItem("LOANS"))))
                .andExpect(jsonPath("$.data.savings").exists())
                .andExpect(jsonPath("$.data.loans").doesNotExist())
                .andExpect(jsonPath("$.data.investments").doesNotExist())
                .andExpect(jsonPath("$.data.penaltiesOutstandingMinor").doesNotExist())
                .andExpect(jsonPath("$.data.pending.loansAwaitingApproval").doesNotExist())
                .andExpect(jsonPath("$.data.pending.membershipApplications").value(0));
        mvc.perform(chama.chair().get("/api/v1/dashboard/me"))
                .andExpect(jsonPath("$.data.loans").doesNotExist())
                .andExpect(jsonPath("$.data.savings.accounts.length()").value(1));
    }

    // ---- helpers --------------------------------------------------------------------------------

    record Activity(TestChama chama, Account member) {
    }

    /** A member who joined two months ago, paid KES 5,000 by M-Pesa, was fined KES 500 and applied for a loan. */
    Activity chamaWithActivity(String chair, String memberName) throws Exception {
        TestChama chama = accounts.onboard(chair);
        mvc.perform(chama.chair().put("/api/v1/chama/settings", """
                        {"contributionAmountMinor":%d,"contributionFrequency":"MONTHLY","contributionDueDay":1,
                         "lateGraceDays":5,"latePenaltyBasisPoints":200,"loanMaxMultiplier":3,"loanInterestBasisPoints":1000,
                         "loanMinMembershipMonths":0,"loanGuarantorThresholdMinor":100000000,"loanGuarantorsRequired":2,
                         "approvalQuorum":2}""".formatted(KES_5000)))
                .andExpect(status().isOk());
        Account member = accounts.member(chama, memberName);
        owner.jdbc().update("update member.memberships set joined_at = now() - interval '60 days' where user_id = ?",
                member.userId());
        mvc.perform(chama.chair().post("/api/v1/contributions", """
                        {"memberUserId":"%s","amountMinor":%d,"method":"MPESA","reference":"D%s"}"""
                        .formatted(member.userId(), KES_5000, Long.toString(System.nanoTime(), 36).toUpperCase())))
                .andExpect(status().isCreated());
        mvc.perform(chama.chair().post("/api/v1/penalties/fines", """
                        {"memberUserId":"%s","amountMinor":%d,"reason":"Missed the meeting"}""".formatted(member.userId(), FINE)))
                .andExpect(status().isCreated());
        mvc.perform(member.post("/api/v1/loans", """
                        {"principalMinor":100000,"termMonths":3,"purpose":"Stock for the shop"}"""))
                .andExpect(status().isCreated());
        return new Activity(chama, member);
    }
}
