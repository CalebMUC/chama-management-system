package ke.chama.modules.account;

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

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Savings products, member accounts and loan products over HTTP. Chamas contribute KES 5,000 a
 * month due on the 1st, and members' joining dates (and their accounts' opening dates) are moved
 * back, so the current period is already due whatever today is.
 */
@IntegrationTest
class MemberAccountIntegrationTests {

    static final long KES_5000 = 500_000;
    static final long KES_500 = 50_000;

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
    class Defaults {

        @Test
        void everyChamaStartsWithDepositsANormalLoanAndAnAccountPerMember() throws Exception {
            TestChama chama = monthly("Atieno", 3);
            Account member = owingMember(chama, "Moses");

            mvc.perform(member.get("/api/v1/savings-products"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].code").value("DEP"))
                    .andExpect(jsonPath("$.data[0].ledgerAccountCode").value("2000"))
                    .andExpect(jsonPath("$.data[0].contributionMinor").value(KES_5000))
                    .andExpect(jsonPath("$.data[0].defaultProduct").value(true));
            mvc.perform(member.get("/api/v1/loan-products"))
                    .andExpect(jsonPath("$.data[0].code").value("NORMAL"))
                    .andExpect(jsonPath("$.data[0].maxMultiplier").value(3))
                    .andExpect(jsonPath("$.data[0].defaultProduct").value(true));
            mvc.perform(member.get("/api/v1/member-accounts/me"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.accounts.length()").value(1))
                    .andExpect(jsonPath("$.data.accounts[0].productCode").value("DEP"))
                    .andExpect(jsonPath("$.data.accounts[0].accountNumber").value(org.hamcrest.Matchers.startsWith("DEP-")))
                    .andExpect(jsonPath("$.data.accounts[0].balanceMinor").value(0));
        }

        @Test
        void theDefaultProductsFollowTheChamasSettings() throws Exception {
            TestChama chama = monthly("Wekesa", 3);
            String body = mvc.perform(chama.chair().get("/api/v1/savings-products"))
                    .andReturn().getResponse().getContentAsString();
            UUID deposits = UUID.fromString(JsonPath.read(body, "$.data[0].id"));

            settings(chama, 600_000, 0, 1_200);

            mvc.perform(chama.chair().get("/api/v1/savings-products/{id}", deposits))
                    .andExpect(jsonPath("$.data.contributionMinor").value(600_000));
            mvc.perform(chama.chair().get("/api/v1/loan-products"))
                    .andExpect(jsonPath("$.data[0].interestBasisPoints").value(1_200));
            mvc.perform(chama.chair().put("/api/v1/savings-products/{id}", """
                            {"name":"Deposits","contributionMinor":700000,"allocationPriority":50,"mandatory":true}""",
                            deposits))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("SAVINGS_PRODUCT_DEFAULT_RULE"));
            mvc.perform(chama.chair().post("/api/v1/savings-products/{id}/deactivate", deposits))
                    .andExpect(status().isUnprocessableEntity());
        }

        @Test
        void onlyOfficialsWithThePermissionManageProducts() throws Exception {
            TestChama chama = monthly("Njoroge", 3);
            Account member = accounts.member(chama, "Ruth");

            createProduct(member, "SHR", "Share capital", "SHARE_CAPITAL", KES_500, true)
                    .andExpect(status().isForbidden());
            mvc.perform(member.get("/api/v1/member-accounts")).andExpect(status().isForbidden());
        }
    }

    @Nested
    class Contributions {

        @Test
        void aPaymentClearsEachProductsDueByPriorityAndTheRestGoesToDeposits() throws Exception {
            TestChama chama = monthly("Chebet", 3);
            Account member = owingMember(chama, "Daniel");
            UUID shares = id(createProduct(chama.chair(), "SHR", "Share capital", "SHARE_CAPITAL", KES_500, true)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.ledgerAccountCode").value("3100"))
                    .andExpect(jsonPath("$.data.countsTowardLoanLimit").value(false))
                    .andExpect(jsonPath("$.data.members").value(2)));
            backdateAccounts(shares);

            // KES 3,000 against KES 5,500 owed: shares (priority 10) first, then deposits.
            record(chama.chair(), member, 300_000, null)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.allocations.length()").value(2))
                    .andExpect(jsonPath("$.data.allocations[?(@.productCode=='SHR')].amountMinor").value((int) KES_500))
                    .andExpect(jsonPath("$.data.allocations[?(@.productCode=='DEP')].amountMinor").value(250_000));

            mvc.perform(member.get("/api/v1/contributions/me"))
                    .andExpect(jsonPath("$.data.standing.dueToDateMinor").value(KES_5000 + KES_500))
                    .andExpect(jsonPath("$.data.standing.arrearsMinor").value(250_000))
                    .andExpect(jsonPath("$.data.standing.products[?(@.productCode=='SHR')].arrearsMinor").value(0))
                    .andExpect(jsonPath("$.data.standing.products[?(@.productCode=='DEP')].arrearsMinor").value(250_000))
                    .andExpect(jsonPath("$.data.periods[0].period.amountDueMinor").value(KES_5000 + KES_500))
                    .andExpect(jsonPath("$.data.periods[0].period.products.length()").value(2));
            mvc.perform(member.get("/api/v1/member-accounts/me"))
                    .andExpect(jsonPath("$.data.totalBalanceMinor").value(300_000))
                    .andExpect(jsonPath("$.data.accounts[?(@.productCode=='SHR')].balanceMinor").value((int) KES_500));
            mvc.perform(chama.chair().get("/api/v1/ledger/accounts"))
                    .andExpect(jsonPath("$.data[?(@.code=='3100')].balanceMinor").value((int) KES_500))
                    .andExpect(jsonPath("$.data[?(@.code=='2000')].balanceMinor").value(250_000));
            // Share capital does not back loans: only deposits count.
            mvc.perform(member.get("/api/v1/loans/eligibility"))
                    .andExpect(jsonPath("$.data.savingsMinor").value(250_000));
        }

        @Test
        void anOfficialCanSplitAPaymentIntoAccountsTheMemberHolds() throws Exception {
            TestChama chama = monthly("Mutua", 3);
            Account member = owingMember(chama, "Grace");
            UUID holiday = id(createProduct(chama.chair(), "HOL", "Holiday savings", "CUSTOM", 0, false)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.members").value(0)));

            record(chama.chair(), member, 200_000, List.of(holiday))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("CONTRIBUTION_INVALID_PAYMENT"))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("no open account")));

            UUID account = id(mvc.perform(chama.chair().post("/api/v1/member-accounts", """
                            {"memberUserId":"%s","productId":"%s"}""".formatted(member.userId(), holiday)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.accountNumber").value(org.hamcrest.Matchers.startsWith("HOL-"))));
            mvc.perform(chama.chair().post("/api/v1/member-accounts", """
                            {"memberUserId":"%s","productId":"%s"}""".formatted(member.userId(), holiday)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("MEMBER_ACCOUNT_EXISTS"));

            record(chama.chair(), member, 200_000, List.of(holiday))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.allocations[0].productCode").value("HOL"));

            mvc.perform(member.get("/api/v1/member-accounts/me/{id}/statement", account))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.closingBalanceMinor").value(200_000))
                    .andExpect(jsonPath("$.data.lines.length()").value(1))
                    .andExpect(jsonPath("$.data.lines[0].sourceType").value("CONTRIBUTION"));
            // Holiday savings are still owed in full: deposits for the period are unpaid.
            mvc.perform(member.get("/api/v1/contributions/me"))
                    .andExpect(jsonPath("$.data.standing.arrearsMinor").value(KES_5000));

            mvc.perform(chama.chair().post("/api/v1/member-accounts/{id}/close", "{\"reason\":\"Done saving\"}", account))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("MEMBER_ACCOUNT_STATE"));
        }

        @Test
        void aMandatoryAccountCannotBeClosedWhileTheMemberIsSeated() throws Exception {
            TestChama chama = monthly("Kiprop", 3);
            Account member = owingMember(chama, "Lilian");
            String mine = mvc.perform(member.get("/api/v1/member-accounts/me")).andReturn().getResponse().getContentAsString();
            UUID deposits = UUID.fromString(JsonPath.read(mine, "$.data.accounts[0].id"));

            mvc.perform(chama.chair().post("/api/v1/member-accounts/{id}/close", "{\"reason\":\"Test\"}", deposits))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Every member")));
            mvc.perform(member.get("/api/v1/member-accounts/me/{id}/statement", UUID.randomUUID()))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    class Loans {

        @Test
        void aMemberMayHoldOneLoanPerProductWithinTheirOverallLimit() throws Exception {
            TestChama chama = monthly("Odhiambo", 0);
            Account member = owingMember(chama, "Peter");
            record(chama.chair(), member, 1_000_000, null).andExpect(status().isCreated());   // KES 10,000 of deposits
            UUID emergency = id(mvc.perform(chama.chair().post("/api/v1/loan-products", """
                            {"code":"EMERG","name":"Emergency loan","interestBasisPoints":500,"maxTermMonths":3,
                             "maxMultiplier":3,"minMembershipMonths":0,"guarantorThresholdMinor":100000000,
                             "guarantorsRequired":0}"""))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.defaultProduct").value(false)));

            apply(member, null, 2_000_000, 12).andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.loan.productCode").value("NORMAL"));

            // KES 30,000 limit less the KES 20,000 normal loan applied for.
            mvc.perform(member.get("/api/v1/loans/eligibility?productId={id}", emergency))
                    .andExpect(jsonPath("$.data.productCode").value("EMERG"))
                    .andExpect(jsonPath("$.data.otherLoansMinor").value(2_000_000))
                    .andExpect(jsonPath("$.data.maxLoanMinor").value(1_000_000))
                    .andExpect(jsonPath("$.data.maxTermMonths").value(3));
            apply(member, emergency, 1_000_000, 4)
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("longest term")));
            apply(member, emergency, 1_000_001, 3)
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("LOAN_NOT_ELIGIBLE"));
            apply(member, emergency, 1_000_000, 3).andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.loan.productCode").value("EMERG"))
                    .andExpect(jsonPath("$.data.loan.interestBasisPoints").value(500));

            apply(member, emergency, 100_000, 3)
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("already have an emergency loan")));
            apply(member, null, 100_000, 3)
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("already have a normal loan")));

            mvc.perform(chama.chair().post("/api/v1/loan-products/{id}/deactivate", emergency)).andExpect(status().isOk());
            mvc.perform(member.get("/api/v1/loans/eligibility?productId={id}", emergency))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("LOAN_PRODUCT_INACTIVE"));
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    private TestChama monthly(String chair, int loanMinMembershipMonths) throws Exception {
        TestChama chama = accounts.onboard(chair);
        settings(chama, KES_5000, loanMinMembershipMonths, 1_000);
        return chama;
    }

    private void settings(TestChama chama, long amount, int loanMinMembershipMonths, int interestBp) throws Exception {
        mvc.perform(chama.chair().put("/api/v1/chama/settings", """
                        {"contributionAmountMinor":%d,"contributionFrequency":"MONTHLY","contributionDueDay":1,
                         "lateGraceDays":5,"latePenaltyBasisPoints":200,"loanMaxMultiplier":3,"loanInterestBasisPoints":%d,
                         "loanMinMembershipMonths":%d,"loanGuarantorThresholdMinor":100000000,"loanGuarantorsRequired":2,
                         "approvalQuorum":2}""".formatted(amount, interestBp, loanMinMembershipMonths)))
                .andExpect(status().isOk());
    }

    /** An active member who joined two months ago, so they owe the current period. */
    private Account owingMember(TestChama chama, String firstName) throws Exception {
        Account member = accounts.member(chama, firstName);
        owner.jdbc().update("update member.memberships set joined_at = now() - interval '60 days' where user_id = ?",
                member.userId());
        owner.jdbc().update("update account.member_accounts set opened_on = current_date - 60 where member_user_id = ?",
                member.userId());
        return member;
    }

    /** Accounts in a product added today open today; move them back so they owe the current period too. */
    private void backdateAccounts(UUID productId) {
        owner.jdbc().update("update account.member_accounts set opened_on = current_date - 60 where product_id = ?",
                productId);
    }

    private ResultActions createProduct(Account official, String code, String name, String kind, long contribution,
                                        boolean mandatory) throws Exception {
        return mvc.perform(official.post("/api/v1/savings-products", """
                {"code":"%s","name":"%s","kind":"%s","contributionMinor":%d,"mandatory":%s}"""
                .formatted(code, name, kind, contribution, mandatory)));
    }

    /** All of the amount into the first product named, when products are given; split automatically otherwise. */
    private ResultActions record(Account official, Account member, long amount, List<UUID> products) throws Exception {
        String allocations = products == null ? ""
                : ",\"allocations\":[{\"productId\":\"%s\",\"amountMinor\":%d}]".formatted(products.getFirst(), amount);
        return mvc.perform(official.post("/api/v1/contributions", """
                {"memberUserId":"%s","amountMinor":%d,"method":"CASH"%s}""".formatted(member.userId(), amount, allocations)));
    }

    private ResultActions apply(Account member, UUID productId, long principal, int term) throws Exception {
        return mvc.perform(member.post("/api/v1/loans", """
                {%s"principalMinor":%d,"termMonths":%d,"purpose":"School fees"}"""
                .formatted(productId == null ? "" : "\"productId\":\"" + productId + "\",", principal, term)));
    }

    private static UUID id(ResultActions result) throws Exception {
        return UUID.fromString(JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.data.id"));
    }

}
