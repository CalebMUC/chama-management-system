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

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reports over HTTP. Each chama gets a member who saved KES 5,000 by M-Pesa, was fined KES 500 and
 * applied for a KES 1,000 loan, so every report has something to show.
 */
@IntegrationTest
class ReportIntegrationTests {

    static final long KES_5000 = 500_000;
    static final long FINE = 50_000;
    static final String URL = "/api/v1/reports";

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
    void theFinancialStatementsBalanceAndShowTheFineAsIncome() throws Exception {
        Books books = books("Achieng", "Kevin");

        mvc.perform(books.chama.chair().get(URL + "/financial-position"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assets.totalMinor").value(KES_5000 + FINE))
                .andExpect(jsonPath("$.data.assets.lines[?(@.code=='1020')].amountMinor").value((int) KES_5000))
                .andExpect(jsonPath("$.data.liabilities.totalMinor").value(KES_5000))
                .andExpect(jsonPath("$.data.equity.totalMinor").value(FINE))
                .andExpect(jsonPath("$.data.balanced").value(true));
        mvc.perform(books.chama.chair().get(URL + "/income-statement"))
                .andExpect(jsonPath("$.data.income.totalMinor").value(FINE))
                .andExpect(jsonPath("$.data.income.lines[0].code").value("4100"))
                .andExpect(jsonPath("$.data.expenses.totalMinor").value(0))
                .andExpect(jsonPath("$.data.surplusMinor").value(FINE));
        mvc.perform(books.chama.chair().get(URL + "/income-statement?from=2026-12-01&to=2026-11-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("REPORT_INVALID_PERIOD"));
    }

    @Test
    void theCashBookShowsEveryReceiptWithRunningBalances() throws Exception {
        Books books = books("Chebet", "Daniel");

        mvc.perform(books.chama.chair().get(URL + "/cash-book"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accounts.length()").value(3))
                .andExpect(jsonPath("$.data.receiptsMinor").value(KES_5000))
                .andExpect(jsonPath("$.data.closingBalanceMinor").value(KES_5000));
        mvc.perform(books.chama.chair().get(URL + "/cash-book?account=1020"))
                .andExpect(jsonPath("$.data.accounts.length()").value(1))
                .andExpect(jsonPath("$.data.accounts[0].lines[0].receiptMinor").value(KES_5000))
                .andExpect(jsonPath("$.data.accounts[0].lines[0].balanceMinor").value(KES_5000))
                .andExpect(jsonPath("$.data.accounts[0].lines[0].sourceType").value("CONTRIBUTION"));
        mvc.perform(books.chama.chair().get(URL + "/cash-book?account=4100"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("REPORT_NOT_A_CASH_ACCOUNT"));
    }

    @Test
    void contributionsSavingsAndTheLoanBookListEveryMember() throws Exception {
        Books books = books("Wanjiru", "Faith");
        String member = books.member.userId().toString();

        mvc.perform(books.chama.chair().get(URL + "/contributions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.collectedMinor").value(KES_5000))
                .andExpect(jsonPath("$.data.members[?(@.member.userId=='" + member + "')].standing.totalPaidMinor")
                        .value((int) KES_5000))
                .andExpect(jsonPath("$.data.members[?(@.member.userId=='" + member + "')].member.name")
                        .value("Faith Test"));
        mvc.perform(books.chama.chair().get(URL + "/contributions?from=1990-01-01&to=1990-12-31"))
                .andExpect(jsonPath("$.data.periods.length()").value(0))
                .andExpect(jsonPath("$.data.expectedMinor").value(0));

        mvc.perform(books.chama.chair().get(URL + "/savings"))
                .andExpect(jsonPath("$.data.products[0].code").value("DEP"))
                .andExpect(jsonPath("$.data.totalMinor").value(KES_5000))
                .andExpect(jsonPath("$.data.members[?(@.member.userId=='" + member + "')].totalMinor").value((int) KES_5000));

        mvc.perform(books.chama.chair().get(URL + "/loan-portfolio"))
                .andExpect(jsonPath("$.data.activeLoans").value(0))
                .andExpect(jsonPath("$.data.awaitingApproval").value(1))
                .andExpect(jsonPath("$.data.pipelineMinor").value(100_000))
                .andExpect(jsonPath("$.data.aging.length()").value(5))
                .andExpect(jsonPath("$.data.loans[0].borrower.name").value("Faith Test"))
                .andExpect(jsonPath("$.data.loans[0].loan.status").value("PENDING_APPROVAL"));
    }

    @Test
    void aMemberStatementBringsSavingsLoansAndPenaltiesTogether() throws Exception {
        Books books = books("Otieno", "Brian");

        mvc.perform(books.chama.chair().get(URL + "/members/{id}/statement", books.member.userId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.member.name").value("Brian Test"))
                .andExpect(jsonPath("$.data.savingsMinor").value(KES_5000))
                .andExpect(jsonPath("$.data.penaltiesOutstandingMinor").value(FINE))
                .andExpect(jsonPath("$.data.netPositionMinor").value(KES_5000 - FINE))
                .andExpect(jsonPath("$.data.savings[0].code").value("DEP"))
                .andExpect(jsonPath("$.data.savings[0].lines.length()").value(1))
                .andExpect(jsonPath("$.data.penalties.lines.length()").value(1))
                .andExpect(jsonPath("$.data.loans.length()").value(1))
                .andExpect(jsonPath("$.data.contributions.totalPaidMinor").value(KES_5000));

        mvc.perform(books.member.get(URL + "/members/me/statement"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.member.userId").value(books.member.userId().toString()));
        mvc.perform(books.chama.chair().get(URL + "/members/{id}/statement", java.util.UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("REPORT_MEMBER_NOT_FOUND"));
    }

    @Test
    void ordinaryMembersSeeOnlyTheirOwnStatement() throws Exception {
        TestChama chama = accounts.onboard("Kamau");
        Account member = accounts.member(chama, "Lucy");

        for (String report : new String[]{"/financial-position", "/income-statement", "/cash-book", "/contributions",
                "/loan-portfolio", "/savings"}) {
            mvc.perform(member.get(URL + report)).andExpect(status().isForbidden());
        }
        mvc.perform(member.get(URL + "/members/{id}/statement", chama.chair().userId())).andExpect(status().isForbidden());
        mvc.perform(member.get(URL + "/members/me/statement")).andExpect(status().isOk());
    }

    // ---- helpers --------------------------------------------------------------------------------

    record Books(TestChama chama, Account member) {
    }

    /**
     * A chama contributing KES 5,000 a month due on the 1st (loans from day one, no guarantors), with a
     * member who joined two months ago, paid KES 5,000 by M-Pesa, was fined KES 500 and applied for KES 1,000.
     */
    Books books(String chair, String memberName) throws Exception {
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
                        {"memberUserId":"%s","amountMinor":%d,"method":"MPESA","reference":"R%s"}"""
                        .formatted(member.userId(), KES_5000, Long.toString(System.nanoTime(), 36).toUpperCase())))
                .andExpect(status().isCreated());
        mvc.perform(chama.chair().post("/api/v1/penalties/fines", """
                        {"memberUserId":"%s","amountMinor":%d,"reason":"Missed the meeting"}""".formatted(member.userId(), FINE)))
                .andExpect(status().isCreated());
        mvc.perform(member.post("/api/v1/loans", """
                        {"principalMinor":100000,"termMonths":3,"purpose":"Stock for the shop"}"""))
                .andExpect(status().isCreated());
        return new Books(chama, member);
    }
}
