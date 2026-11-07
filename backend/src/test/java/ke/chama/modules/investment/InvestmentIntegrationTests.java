package ke.chama.modules.investment;

import com.jayway.jsonpath.JsonPath;
import ke.chama.IntegrationTest;
import ke.chama.RecordingEmailSender;
import ke.chama.TestAccounts;
import ke.chama.TestAccounts.Account;
import ke.chama.TestAccounts.TestChama;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Investments over HTTP. Each chama has a treasurer (proposes and manages investments), a
 * secretary and the chairperson (approve them), and KES 100,000 in its M-Pesa account.
 */
@IntegrationTest
public class InvestmentIntegrationTests {

    static final long KES_100000 = 10_000_000;
    static final long KES_50000 = 5_000_000;

    @Autowired
    MockMvc mvc;

    @Autowired
    RecordingEmailSender emails;

    TestAccounts accounts;

    @BeforeEach
    void setUp() {
        accounts = new TestAccounts(mvc, emails);
    }

    @Nested
    class Lifecycle {

        @Test
        void anInvestmentIsApprovedFundedEarnsIsValuedAndClosedThroughTheLedger() throws Exception {
            Officials chama = chama("Achieng", 2);
            UUID id = idOf(propose(chama.treasurer(), "Sanlam money market fund", "MONEY_MARKET_FUND", KES_50000)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.status").value("PROPOSED"))
                    .andExpect(jsonPath("$.data.requiredApprovals").value(2)));

            mvc.perform(chama.chair().post("/api/v1/investments/{id}/approve", id))
                    .andExpect(jsonPath("$.message").value("Approval recorded (1 of 2)"));
            mvc.perform(chama.secretary().post("/api/v1/investments/{id}/approve", id))
                    .andExpect(jsonPath("$.message").value("Investment approved"));

            mvc.perform(chama.treasurer().post("/api/v1/investments/{id}/fund",
                            "{\"method\":\"MPESA\",\"reference\":\"QKINV00001\"}", id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.investment.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.data.investment.bookValueMinor").value(KES_50000));
            accountBalance(chama, "1200", KES_50000);
            accountBalance(chama, "1020", KES_100000 - KES_50000);

            income(chama, id, 50_000, "REINVESTED", null)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.investment.bookValueMinor").value(KES_50000 + 50_000));
            income(chama, id, 20_000, "MPESA", "QKINV00002")
                    .andExpect(jsonPath("$.data.investment.incomeMinor").value(70_000));
            mvc.perform(chama.treasurer().post("/api/v1/investments/{id}/valuations",
                            "{\"valueMinor\":5200000,\"note\":\"October statement\"}", id))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.investment.unrealisedGainMinor").value(5_200_000 - 5_050_000));
            mvc.perform(chama.treasurer().post("/api/v1/investments/{id}/withdrawals",
                            "{\"amountMinor\":1000000,\"method\":\"MPESA\",\"reference\":\"QKINV00003\"}", id))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.investment.bookValueMinor").value(4_050_000));

            mvc.perform(chama.chair().get("/api/v1/investments/portfolio"))
                    .andExpect(jsonPath("$.data.activeCount").value(1))
                    .andExpect(jsonPath("$.data.bookValueMinor").value(4_050_000))
                    .andExpect(jsonPath("$.data.incomeMinor").value(70_000))
                    .andExpect(jsonPath("$.data.byType[0].type").value("MONEY_MARKET_FUND"));

            mvc.perform(chama.treasurer().post("/api/v1/investments/{id}/close",
                            "{\"proceedsMinor\":4300000,\"method\":\"MPESA\",\"reference\":\"QKINV00004\"}", id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.investment.status").value("CLOSED"))
                    .andExpect(jsonPath("$.data.investment.realisedGainMinor").value(250_000))
                    .andExpect(jsonPath("$.data.transactions.length()").value(5))
                    .andExpect(jsonPath("$.data.valuations.length()").value(1));

            accountBalance(chama, "1200", 0);
            accountBalance(chama, "4200", 70_000 + 250_000);
            accountBalance(chama, "1020", KES_100000 - KES_50000 + 20_000 + 1_000_000 + 4_300_000);
            mvc.perform(chama.chair().get("/api/v1/investments/portfolio"))
                    .andExpect(jsonPath("$.data.activeCount").value(0))
                    .andExpect(jsonPath("$.data.realisedGainMinor").value(250_000));

            String log = mvc.perform(chama.chair().get("/api/v1/audit-log?size=100")).andReturn().getResponse().getContentAsString();
            List<String> actions = JsonPath.read(log, "$.data[?(@.targetType=='INVESTMENT')].action");
            assertThat(actions).contains("INVESTMENT_PROPOSED", "INVESTMENT_APPROVED", "INVESTMENT_FUNDED",
                    "INVESTMENT_INCOME_RECORDED", "INVESTMENT_VALUED", "INVESTMENT_WITHDRAWAL", "INVESTMENT_CLOSED");
        }

        @Test
        void aLossOnClosingIsChargedAgainstInvestmentIncome() throws Exception {
            Officials chama = chama("Chebet", 1);
            UUID id = funded(chama, 1_000_000);

            mvc.perform(chama.treasurer().post("/api/v1/investments/{id}/close",
                            "{\"proceedsMinor\":800000,\"method\":\"MPESA\",\"reference\":\"QKLOSS0001\"}", id))
                    .andExpect(jsonPath("$.data.investment.realisedGainMinor").value(-200_000));
            accountBalance(chama, "4200", -200_000);
            accountBalance(chama, "1200", 0);
        }
    }

    /**
     * Savings kept in a money market fund, part of it used to set up an Airbnb unit whose bookings
     * come in by M-Pesa, whose rent and bills are paid as expenses charged to it, and whose shortfalls
     * are covered by withdrawing from the fund.
     */
    @Nested
    class MoneyMarketFundAndAirbnb {

        @Test
        void eachInvestmentShowsWhetherItIsProfitable() throws Exception {
            Officials chama = chama("Mokeira", 1);                                   // KES 100,000 in M-Pesa

            // Savings go to the CIC money market fund, then a later top-up.
            UUID cic = idOf(propose(chama.treasurer(), "CIC money market fund", "MONEY_MARKET_FUND", 8_000_000));
            approve(chama, cic);
            fund(chama, cic);                                                        // M-Pesa 20,000
            mvc.perform(chama.treasurer().post("/api/v1/investments/{id}/deposits",
                            "{\"amountMinor\":1000000,\"method\":\"MPESA\",\"reference\":\"QKCIC00002\",\"note\":\"Top-up\"}", cic))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.investment.bookValueMinor").value(9_000_000))
                    .andExpect(jsonPath("$.data.investment.depositedMinor").value(1_000_000));   // M-Pesa 10,000
            income(chama, cic, 100_000, "REINVESTED", null);                          // CIC 91,000

            // Withdraw from CIC to set up the Airbnb unit.
            withdraw(chama, cic, 3_000_000, "QKCIC00003");                            // CIC 61,000; M-Pesa 40,000
            UUID airbnb = idOf(propose(chama.treasurer(), "Airbnb unit, Kilimani", "BUSINESS", 3_000_000));
            approve(chama, airbnb);
            fund(chama, airbnb);                                                     // M-Pesa 10,000

            // Guests pay to the chama's M-Pesa; rent, cleaner and Wi-Fi are expenses charged to the unit.
            income(chama, airbnb, 1_200_000, "MPESA", "QKGUEST001");                  // M-Pesa 22,000
            unitExpense(chama, airbnb, 1_500_000, "Landlord", "November rent");
            unitExpense(chama, airbnb, 200_000, "Cleaner", "Cleaning, 4 visits");
            unitExpense(chama, airbnb, 300_000, "Safaricom Home", "Wi-Fi");          // M-Pesa 2,000

            // The bookings did not cover the electricity: withdraw from CIC to pay it.
            UUID power = unitExpenseAwaitingPayment(chama, airbnb, 400_000, "KPLC", "Electricity tokens");
            payExpense(chama, power, "QKKPLC0001").andExpect(status().isUnprocessableEntity());
            mvc.perform(chama.treasurer().post("/api/v1/investments/{id}/valuations", "{\"valueMinor\":6150000}", cic));
            withdraw(chama, cic, 500_000, "QKCIC00004");                              // CIC 56,000; M-Pesa 7,000
            payExpense(chama, power, "QKKPLC0001").andExpect(status().isOk());

            mvc.perform(chama.chair().get("/api/v1/investments/{id}", airbnb))
                    .andExpect(jsonPath("$.data.investment.performance.incomeMinor").value(1_200_000))
                    .andExpect(jsonPath("$.data.investment.performance.expensesMinor").value(2_400_000))
                    .andExpect(jsonPath("$.data.investment.performance.totalReturnMinor").value(-1_200_000))
                    .andExpect(jsonPath("$.data.investment.performance.profitable").value(false));
            mvc.perform(chama.chair().get("/api/v1/investments/{id}", cic))
                    .andExpect(jsonPath("$.data.investment.latestValuationMinor").value(5_650_000))
                    .andExpect(jsonPath("$.data.investment.unrealisedGainMinor").value(50_000))
                    .andExpect(jsonPath("$.data.investment.performance.investedMinor").value(9_000_000))
                    .andExpect(jsonPath("$.data.investment.performance.totalReturnMinor").value(100_000 + 50_000))
                    .andExpect(jsonPath("$.data.investment.performance.returnBasisPoints").value(167))
                    .andExpect(jsonPath("$.data.investment.performance.profitable").value(true));

            mvc.perform(chama.chair().get("/api/v1/expenses?investmentId={id}", airbnb))
                    .andExpect(jsonPath("$.data.length()").value(4))
                    .andExpect(jsonPath("$.data[0].investmentName").value("Airbnb unit, Kilimani"));
            mvc.perform(chama.chair().get("/api/v1/investments/portfolio"))
                    .andExpect(jsonPath("$.data.investedMinor").value(12_000_000))
                    .andExpect(jsonPath("$.data.incomeMinor").value(1_300_000))
                    .andExpect(jsonPath("$.data.expensesMinor").value(2_400_000))
                    .andExpect(jsonPath("$.data.totalReturnMinor").value(1_300_000 - 2_400_000 + 50_000));
            accountBalance(chama, "1020", 300_000);                                   // 7,000 less the 4,000 electricity
            accountBalance(chama, "1200", 5_600_000 + 3_000_000);
        }

        @Test
        void expensesAreChargedOnlyToActiveInvestments() throws Exception {
            Officials chama = chama("Wairimu", 1);
            UUID proposed = idOf(propose(chama.treasurer(), "Plot", "LAND", 1_000_000));

            mvc.perform(chama.treasurer().post("/api/v1/expenses", """
                            {"amountMinor":10000,"payee":"Surveyor","description":"Site visit","investmentId":"%s"}"""
                            .formatted(proposed)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("EXPENSE_INVESTMENT_INVALID"));
            mvc.perform(chama.treasurer().post("/api/v1/expenses", """
                            {"amountMinor":10000,"payee":"Surveyor","description":"Site visit","investmentId":"%s"}"""
                            .formatted(UUID.randomUUID())))
                    .andExpect(jsonPath("$.error.code").value("EXPENSE_INVESTMENT_INVALID"));
        }

        private void approve(Officials chama, UUID id) throws Exception {
            mvc.perform(chama.secretary().post("/api/v1/investments/{id}/approve", id))
                    .andExpect(jsonPath("$.data.status").value("APPROVED"));
        }

        private void fund(Officials chama, UUID id) throws Exception {
            mvc.perform(chama.treasurer().post("/api/v1/investments/{id}/fund",
                    "{\"method\":\"MPESA\",\"reference\":\"QK%s\"}".formatted(ref()), id)).andExpect(status().isOk());
        }

        private void withdraw(Officials chama, UUID id, long amount, String reference) throws Exception {
            mvc.perform(chama.treasurer().post("/api/v1/investments/{id}/withdrawals",
                    "{\"amountMinor\":%d,\"method\":\"MPESA\",\"reference\":\"%s\"}".formatted(amount, reference), id))
                    .andExpect(status().isCreated());
        }

        private void unitExpense(Officials chama, UUID investment, long amount, String payee, String description)
                throws Exception {
            payExpense(chama, unitExpenseAwaitingPayment(chama, investment, amount, payee, description), "QK" + ref())
                    .andExpect(status().isOk());
        }

        private UUID unitExpenseAwaitingPayment(Officials chama, UUID investment, long amount, String payee,
                                                String description) throws Exception {
            UUID id = idOf(mvc.perform(chama.treasurer().post("/api/v1/expenses", """
                    {"amountMinor":%d,"payee":"%s","description":"%s","investmentId":"%s"}"""
                    .formatted(amount, payee, description, investment))).andExpect(status().isCreated()));
            mvc.perform(chama.secretary().post("/api/v1/expenses/{id}/approve", id))
                    .andExpect(jsonPath("$.data.status").value("APPROVED"));
            return id;
        }

        private ResultActions payExpense(Officials chama, UUID expense, String reference) throws Exception {
            return mvc.perform(chama.treasurer().post("/api/v1/expenses/{id}/pay",
                    "{\"method\":\"MPESA\",\"reference\":\"%s\"}".formatted(reference), expense));
        }

        private static String ref() {
            return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        }
    }

    @Nested
    class Accountability {

        @Test
        void whoeverProposesCannotApproveAndNothingIsFundedBeforeApproval() throws Exception {
            Officials chama = chama("Kamau", 1);
            UUID id = idOf(propose(chama.chair(), "Plot in Juja", "LAND", KES_50000));

            mvc.perform(chama.chair().post("/api/v1/investments/{id}/approve", id))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("INVESTMENT_SELF_APPROVAL"));
            mvc.perform(chama.chair().post("/api/v1/investments/{id}/fund", "{\"method\":\"MPESA\",\"reference\":\"QKX1\"}", id))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("INVESTMENT_WRONG_STATUS"));
            mvc.perform(chama.treasurer().post("/api/v1/investments/{id}/approve", id))
                    .andExpect(status().isForbidden());
            mvc.perform(chama.secretary().post("/api/v1/investments/{id}/reject", "{\"reason\":\"Title deed unclear\"}", id))
                    .andExpect(jsonPath("$.data.status").value("REJECTED"));
        }

        @Test
        void membersSeeInvestmentsButCannotPropose() throws Exception {
            Officials chama = chama("Otieno", 1);
            UUID id = idOf(propose(chama.treasurer(), "T-bill 91 days", "TREASURY_BILL", KES_50000));
            Account member = accounts.member(chama.chama(), "Brian");

            propose(member, "My idea", "OTHER", 100).andExpect(status().isForbidden());
            mvc.perform(member.get("/api/v1/investments")).andExpect(jsonPath("$.data[0].name").value("T-bill 91 days"));
            mvc.perform(member.get("/api/v1/investments/{id}", id)).andExpect(status().isOk());
            mvc.perform(member.get("/api/v1/investments/portfolio")).andExpect(status().isOk());
        }

        @Test
        void fundingNeedsTheMoneyAndReferencesAreUsedOnce() throws Exception {
            Officials chama = chama("Wekesa", 1);
            UUID first = approved(chama, 1_000_000);
            UUID second = approved(chama, 1_000_000);

            mvc.perform(chama.treasurer().post("/api/v1/investments/{id}/fund", "{\"method\":\"CASH\"}", first))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("INVESTMENT_INSUFFICIENT_FUNDS"))
                    .andExpect(jsonPath("$.message").value(containsString("cash on hand holds only KES 0.00")));
            mvc.perform(chama.treasurer().post("/api/v1/investments/{id}/fund",
                    "{\"method\":\"MPESA\",\"reference\":\"QKDUP00001\"}", first)).andExpect(status().isOk());
            mvc.perform(chama.treasurer().post("/api/v1/investments/{id}/fund",
                            "{\"method\":\"MPESA\",\"reference\":\"qkdup00001\"}", second))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("INVESTMENT_DUPLICATE_REFERENCE"));
        }

        @Test
        void theLicenceMustIncludeInvestments() throws Exception {
            Officials chama = chama("Barasa", 1);
            mvc.perform(accounts.platformAdmin().put("/api/v1/platform/chamas/{id}/license",
                    "{\"features\":[\"CONTRIBUTIONS\"]}", chama.chama().id())).andExpect(status().isOk());

            mvc.perform(chama.chair().get("/api/v1/investments"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("LICENSE_FEATURE_NOT_INCLUDED"));
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    record Officials(TestChama chama, Account treasurer, Account secretary) {
        Account chair() {
            return chama.chair();
        }
    }

    /** A chama with the given approval quorum, a treasurer, a secretary and KES 100,000 in M-Pesa. */
    Officials chama(String chair, int quorum) throws Exception {
        TestChama chama = accounts.onboard(chair);
        mvc.perform(chama.chair().put("/api/v1/chama/settings", """
                        {"contributionAmountMinor":500000,"contributionFrequency":"MONTHLY","contributionDueDay":28,
                         "lateGraceDays":5,"latePenaltyBasisPoints":0,"loanMaxMultiplier":3,"loanInterestBasisPoints":1000,
                         "loanMinMembershipMonths":3,"loanGuarantorThresholdMinor":5000000,"loanGuarantorsRequired":2,
                         "approvalQuorum":%d}""".formatted(quorum)))
                .andExpect(status().isOk());
        Account treasurer = official(chama, "Tom", "TREASURER");
        Account secretary = official(chama, "Sara", "SECRETARY");
        Account saver = accounts.member(chama, "Kevin");
        mvc.perform(chama.chair().post("/api/v1/contributions",
                        "{\"memberUserId\":\"%s\",\"amountMinor\":%d,\"method\":\"MPESA\",\"reference\":\"QK%s\"}"
                                .formatted(saver.userId(), KES_100000, UUID.randomUUID().toString().substring(0, 8))))
                .andExpect(status().isCreated());
        return new Officials(chama, treasurer, secretary);
    }

    private Account official(TestChama chama, String firstName, String role) throws Exception {
        Account account = accounts.member(chama, firstName);
        mvc.perform(chama.chair().put("/api/v1/members/{m}/roles", "{\"roles\":[\"%s\"]}".formatted(role),
                accounts.membershipId(account))).andExpect(status().isOk());
        return account;
    }

    /** Proposed by the treasurer and approved (quorum 1) by the secretary. */
    UUID approved(Officials chama, long amount) throws Exception {
        UUID id = idOf(propose(chama.treasurer(), "Fixed deposit", "FIXED_DEPOSIT", amount));
        mvc.perform(chama.secretary().post("/api/v1/investments/{id}/approve", id))
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
        return id;
    }

    UUID funded(Officials chama, long amount) throws Exception {
        UUID id = approved(chama, amount);
        mvc.perform(chama.treasurer().post("/api/v1/investments/{id}/fund",
                        "{\"method\":\"MPESA\",\"reference\":\"QK%s\"}".formatted(UUID.randomUUID().toString().substring(0, 8)), id))
                .andExpect(status().isOk());
        return id;
    }

    ResultActions propose(Account official, String name, String type, long amount) throws Exception {
        return mvc.perform(official.post("/api/v1/investments", """
                {"name":"%s","type":"%s","institution":"Sanlam","amountMinor":%d,"expectedReturnBasisPoints":1200}"""
                .formatted(name, type, amount)));
    }

    ResultActions income(Officials chama, UUID id, long amount, String method, String reference) throws Exception {
        return mvc.perform(chama.treasurer().post("/api/v1/investments/{id}/income", """
                {"amountMinor":%d,"method":"%s"%s}""".formatted(amount, method,
                reference == null ? "" : ",\"reference\":\"" + reference + "\""), id));
    }

    void accountBalance(Officials chama, String code, long expected) throws Exception {
        mvc.perform(chama.chair().get("/api/v1/ledger/accounts"))
                .andExpect(jsonPath("$.data[?(@.code=='%s')].balanceMinor".formatted(code)).value((int) expected));
    }

    static UUID idOf(ResultActions result) throws Exception {
        return UUID.fromString(JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.data.id"));
    }
}
