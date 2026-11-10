package ke.chama.modules.report;

import com.jayway.jsonpath.JsonPath;
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

import java.util.UUID;

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
                .andExpect(jsonPath("$.data.recentEntries[0].sourceType").value("PENALTY"))
                .andExpect(jsonPath("$.data.keyFigures.totalContributionsMinor").value(KES_5000))
                .andExpect(jsonPath("$.data.keyFigures.contributionsYearToDateMinor").value(KES_5000))
                .andExpect(jsonPath("$.data.keyFigures.cashMinor").value(KES_5000))
                .andExpect(jsonPath("$.data.keyFigures.penaltiesOutstandingMinor").value(FINE))
                .andExpect(jsonPath("$.data.contributions.totalCollectedMinor").value(KES_5000))
                .andExpect(jsonPath("$.data.contributions.paymentsThisMonth").value(1))
                .andExpect(jsonPath("$.data.penalties.outstandingCount").value(1))
                .andExpect(jsonPath("$.data.penalties.finesYearToDateMinor").value(FINE))
                .andExpect(jsonPath("$.data.penalties.topOwing[0].name").value("Kevin Test"))
                .andExpect(jsonPath("$.data.topMembers[0].name").value("Kevin Test"))
                .andExpect(jsonPath("$.data.topMembers[0].contributedMinor").value(KES_5000))
                .andExpect(jsonPath("$.data.topInvestments.length()").value(0))
                .andExpect(jsonPath("$.data.trends.length()").value(6))
                .andExpect(jsonPath("$.data.trends[5].contributionsMinor").value(KES_5000))
                .andExpect(jsonPath("$.data.pending.paymentsAwaitingReview").value(0));
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
                .andExpect(jsonPath("$.data.penaltiesOutstandingMinor").value(FINE))
                .andExpect(jsonPath("$.data.netPositionMinor").value(KES_5000 - FINE))
                .andExpect(jsonPath("$.data.contributions.contributedYearToDateMinor").value(KES_5000))
                .andExpect(jsonPath("$.data.contributions.rank").value(1))
                .andExpect(jsonPath("$.data.loans.loans[0].purpose").value("Stock for the shop"))
                .andExpect(jsonPath("$.data.loans.loans[0].principalMinor").value(100_000))
                .andExpect(jsonPath("$.data.penalties.outstanding[0].amountMinor").value(FINE))
                .andExpect(jsonPath("$.data.submissions.pending").value(0));
        mvc.perform(activity.member().get("/api/v1/dashboard")).andExpect(status().isForbidden());
        mvc.perform(activity.member().get("/api/v1/dashboard/top-members")).andExpect(status().isForbidden());
    }

    @Test
    void everyMemberSeesTheChamaOverviewWithoutAnyoneElsesDetails() throws Exception {
        Activity activity = chamaWithActivity("Wanjiru", "Brian");

        mvc.perform(activity.member().get("/api/v1/dashboard/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.members.active").value(2))
                .andExpect(jsonPath("$.data.keyFigures.totalContributionsMinor").value(KES_5000))
                .andExpect(jsonPath("$.data.keyFigures.cashMinor").value(KES_5000))
                .andExpect(jsonPath("$.data.keyFigures.investmentsMarketValueMinor").value(0))
                .andExpect(jsonPath("$.data.savings.totalMinor").value(KES_5000))
                .andExpect(jsonPath("$.data.contributions.collectedMinor").value(KES_5000))
                .andExpect(jsonPath("$.data.loans.awaitingApproval").value(1))
                .andExpect(jsonPath("$.data.penalties.outstandingMinor").value(FINE))
                .andExpect(jsonPath("$.data.penalties.topOwing.length()").value(0))
                .andExpect(jsonPath("$.data.investments.active").value(0))
                .andExpect(jsonPath("$.data.yearToDate.surplusMinor").value(FINE))
                .andExpect(jsonPath("$.data.trends.length()").value(6))
                // Nothing that names a member or is an official's work.
                .andExpect(jsonPath("$.data.topMembers").doesNotExist())
                .andExpect(jsonPath("$.data.recentEntries").doesNotExist())
                .andExpect(jsonPath("$.data.pending").doesNotExist());

        // Without INVESTMENT_VIEW the investment figures are left out.
        mvc.perform(activity.chama().chair().put("/api/v1/roles/MEMBER/permissions",
                        "{\"permissions\":[\"CHAMA_VIEW\",\"LOAN_APPLY\",\"PAYMENT_SUBMIT\"]}"))
                .andExpect(status().isOk());
        mvc.perform(activity.member().get("/api/v1/dashboard/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.investments").doesNotExist())
                .andExpect(jsonPath("$.data.topInvestments").doesNotExist())
                .andExpect(jsonPath("$.data.keyFigures.investmentsMarketValueMinor").doesNotExist())
                .andExpect(jsonPath("$.data.keyFigures.cashMinor").value(KES_5000));
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
                .andExpect(jsonPath("$.data.topInvestments").doesNotExist())
                .andExpect(jsonPath("$.data.keyFigures.loansOutstandingMinor").doesNotExist())
                .andExpect(jsonPath("$.data.trends[0].loansDisbursedMinor").doesNotExist())
                .andExpect(jsonPath("$.data.pending.loansAwaitingApproval").doesNotExist())
                .andExpect(jsonPath("$.data.pending.membershipApplications").value(0));
        mvc.perform(chama.chair().get("/api/v1/dashboard/me"))
                .andExpect(jsonPath("$.data.loans").doesNotExist())
                .andExpect(jsonPath("$.data.savings.accounts.length()").value(1));
    }

    @Test
    void membersAndInvestmentsAreRankedByHowTheyPerform() throws Exception {
        TestChama chama = accounts.onboard("Njeri");
        settings(chama, 1);
        Account achieng = accounts.member(chama, "Achieng");
        Account kevin = accounts.member(chama, "Kevin");
        Account treasurer = accounts.member(chama, "Tom");
        mvc.perform(chama.chair().put("/api/v1/members/{m}/roles", "{\"roles\":[\"TREASURER\"]}",
                accounts.membershipId(treasurer))).andExpect(status().isOk());
        contribute(chama, achieng, 2 * KES_5000);
        contribute(chama, kevin, KES_5000);

        // Two KES 5,000 fixed deposits; only the first has earned anything (KES 500, 10%).
        UUID earning = fundedInvestment(chama, treasurer, "Sanlam fixed deposit");
        UUID idle = fundedInvestment(chama, treasurer, "Britam fixed deposit");
        mvc.perform(treasurer.post("/api/v1/investments/{id}/income",
                        "{\"amountMinor\":%d,\"method\":\"MPESA\",\"reference\":\"%s\"}".formatted(FINE, reference()),
                        earning))
                .andExpect(status().isCreated());

        mvc.perform(chama.chair().get("/api/v1/dashboard/top-members?limit=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.members.length()").value(2))
                .andExpect(jsonPath("$.data.members[0].name").value("Achieng Test"))
                .andExpect(jsonPath("$.data.members[0].rank").value(1))
                .andExpect(jsonPath("$.data.members[0].contributedMinor").value(2 * KES_5000))
                .andExpect(jsonPath("$.data.members[0].savingsMinor").value(2 * KES_5000))
                .andExpect(jsonPath("$.data.members[1].name").value("Kevin Test"));
        mvc.perform(chama.chair().get("/api/v1/dashboard/top-members?from=2020-01-01&to=2020-12-31"))
                .andExpect(jsonPath("$.data.members.length()").value(0));
        mvc.perform(chama.chair().get("/api/v1/dashboard/top-members?from=2026-02-01&to=2026-01-01"))
                .andExpect(status().is4xxClientError());

        mvc.perform(chama.chair().get("/api/v1/dashboard/top-investments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.investments.length()").value(2))
                .andExpect(jsonPath("$.data.investments[0].investmentId").value(earning.toString()))
                .andExpect(jsonPath("$.data.investments[0].totalReturnMinor").value(FINE))
                .andExpect(jsonPath("$.data.investments[0].returnBasisPoints").value(1000))
                .andExpect(jsonPath("$.data.investments[0].profitable").value(true))
                .andExpect(jsonPath("$.data.investments[1].investmentId").value(idle.toString()))
                .andExpect(jsonPath("$.data.investments[1].profitable").value(false));

        mvc.perform(chama.chair().get("/api/v1/dashboard"))
                .andExpect(jsonPath("$.data.keyFigures.totalContributionsMinor").value(3 * KES_5000))
                .andExpect(jsonPath("$.data.keyFigures.investmentsMarketValueMinor").value(2 * KES_5000))
                .andExpect(jsonPath("$.data.investments.active").value(2))
                .andExpect(jsonPath("$.data.investments.totalReturnMinor").value(FINE))
                .andExpect(jsonPath("$.data.investments.profitable").value(1))
                .andExpect(jsonPath("$.data.topMembers[0].name").value("Achieng Test"))
                .andExpect(jsonPath("$.data.topInvestments[0].name").value("Sanlam fixed deposit"));

        mvc.perform(chama.chair().get("/api/v1/dashboard/trends?months=3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.months.length()").value(3))
                .andExpect(jsonPath("$.data.months[2].contributionsMinor").value(3 * KES_5000))
                .andExpect(jsonPath("$.data.months[2].contributionPayments").value(2))
                .andExpect(jsonPath("$.data.months[2].incomeMinor").value(FINE));
        mvc.perform(chama.chair().get("/api/v1/dashboard/trends?months=0")).andExpect(status().is4xxClientError());

        mvc.perform(kevin.get("/api/v1/dashboard/me"))
                .andExpect(jsonPath("$.data.contributions.rank").value(2))
                .andExpect(jsonPath("$.data.topInvestments[0].name").value("Sanlam fixed deposit"));
    }

    // ---- helpers --------------------------------------------------------------------------------

    record Activity(TestChama chama, Account member) {
    }

    /** A member who joined two months ago, paid KES 5,000 by M-Pesa, was fined KES 500 and applied for a loan. */
    Activity chamaWithActivity(String chair, String memberName) throws Exception {
        TestChama chama = accounts.onboard(chair);
        settings(chama, 2);
        Account member = accounts.member(chama, memberName);
        owner.jdbc().update("update member.memberships set joined_at = now() - interval '60 days' where user_id = ?",
                member.userId());
        contribute(chama, member, KES_5000);
        mvc.perform(chama.chair().post("/api/v1/penalties/fines", """
                        {"memberUserId":"%s","amountMinor":%d,"reason":"Missed the meeting"}""".formatted(member.userId(), FINE)))
                .andExpect(status().isCreated());
        mvc.perform(member.post("/api/v1/loans", """
                        {"principalMinor":100000,"termMonths":3,"purpose":"Stock for the shop"}"""))
                .andExpect(status().isCreated());
        return new Activity(chama, member);
    }

    void settings(TestChama chama, int quorum) throws Exception {
        mvc.perform(chama.chair().put("/api/v1/chama/settings", """
                        {"contributionAmountMinor":%d,"contributionFrequency":"MONTHLY","contributionDueDay":1,
                         "lateGraceDays":5,"latePenaltyBasisPoints":200,"loanMaxMultiplier":3,"loanInterestBasisPoints":1000,
                         "loanMinMembershipMonths":0,"loanGuarantorThresholdMinor":100000000,"loanGuarantorsRequired":2,
                         "approvalQuorum":%d}""".formatted(KES_5000, quorum)))
                .andExpect(status().isOk());
    }

    void contribute(TestChama chama, Account member, long amount) throws Exception {
        mvc.perform(chama.chair().post("/api/v1/contributions", """
                        {"memberUserId":"%s","amountMinor":%d,"method":"MPESA","reference":"%s"}"""
                        .formatted(member.userId(), amount, reference())))
                .andExpect(status().isCreated());
    }

    /** KES 5,000 proposed by the treasurer, approved by the chair (quorum 1) and paid out by M-Pesa. */
    UUID fundedInvestment(TestChama chama, Account treasurer, String name) throws Exception {
        String body = mvc.perform(treasurer.post("/api/v1/investments", """
                        {"name":"%s","type":"FIXED_DEPOSIT","institution":"Sanlam","amountMinor":%d,"expectedReturnBasisPoints":1200}"""
                        .formatted(name, KES_5000)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(body, "$.data.id"));
        mvc.perform(chama.chair().post("/api/v1/investments/{id}/approve", id))
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
        mvc.perform(treasurer.post("/api/v1/investments/{id}/fund",
                        "{\"method\":\"MPESA\",\"reference\":\"%s\"}".formatted(reference()), id))
                .andExpect(status().isOk());
        return id;
    }

    static String reference() {
        return "D" + Long.toString(System.nanoTime(), 36).toUpperCase();
    }
}
