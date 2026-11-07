package ke.chama.modules.expense;

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
 * Expenses over HTTP. Each chama has a treasurer (records and pays expenses), a secretary and the
 * chairperson (approve them), and KES 20,000 in its M-Pesa account from a member's contribution.
 */
@IntegrationTest
public class ExpenseIntegrationTests {

    static final long KES_3000 = 300_000;
    static final long KES_20000 = 2_000_000;

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
        void anExpenseIsApprovedByQuorumPaidAndPostedToTheLedger() throws Exception {
            Officials chama = chama("Achieng", 2);

            UUID expenseId = idOf(record(chama.treasurer(), KES_3000, "Community Hall", "Hall hire for the AGM", null)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.status").value("PENDING_APPROVAL"))
                    .andExpect(jsonPath("$.data.accountCode").value("5000"))
                    .andExpect(jsonPath("$.data.accountName").value("Operating expenses"))
                    .andExpect(jsonPath("$.data.requiredApprovals").value(2)));

            mvc.perform(chama.chair().post("/api/v1/expenses/{id}/approve", "{\"note\":\"Agreed at the meeting\"}", expenseId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Approval recorded (1 of 2)"));
            mvc.perform(chama.secretary().post("/api/v1/expenses/{id}/approve", expenseId))
                    .andExpect(jsonPath("$.message").value("Expense approved"))
                    .andExpect(jsonPath("$.data.approvals.length()").value(2));

            pay(chama.treasurer(), expenseId, "MPESA", "QKHALL0001")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("PAID"))
                    .andExpect(jsonPath("$.data.paymentReference").value("QKHALL0001"))
                    .andExpect(jsonPath("$.data.ledgerEntryId").isNotEmpty());

            mvc.perform(chama.chair().get("/api/v1/ledger/accounts"))
                    .andExpect(jsonPath("$.data[?(@.code=='5000')].balanceMinor").value((int) KES_3000))
                    .andExpect(jsonPath("$.data[?(@.code=='1020')].balanceMinor").value((int) (KES_20000 - KES_3000)));
            mvc.perform(chama.chair().get("/api/v1/expenses/summary"))
                    .andExpect(jsonPath("$.data.paidMinor").value(KES_3000))
                    .andExpect(jsonPath("$.data.paidCount").value(1))
                    .andExpect(jsonPath("$.data.byAccount[0].accountCode").value("5000"))
                    .andExpect(jsonPath("$.data.awaitingPaymentMinor").value(0));

            String log = mvc.perform(chama.chair().get("/api/v1/audit-log?size=100")).andReturn().getResponse().getContentAsString();
            List<String> actions = JsonPath.read(log, "$.data[?(@.targetType=='EXPENSE')].action");
            assertThat(actions).contains("EXPENSE_REQUESTED", "EXPENSE_APPROVAL_GIVEN", "EXPENSE_APPROVED", "EXPENSE_PAID");
        }

        @Test
        void anApproverRejectsWithAReasonAndTheRequesterCancels() throws Exception {
            Officials chama = chama("Chebet", 2);
            UUID rejected = idOf(record(chama.treasurer(), KES_3000, "Caterer", "Lunch", null));
            UUID cancelled = idOf(record(chama.treasurer(), KES_3000, "Printer", "Minutes", null));

            mvc.perform(chama.secretary().post("/api/v1/expenses/{id}/reject", "{\"reason\":\"Members bring lunch\"}", rejected))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("REJECTED"))
                    .andExpect(jsonPath("$.data.rejectionReason").value("Members bring lunch"));
            mvc.perform(chama.chair().post("/api/v1/expenses/{id}/cancel", cancelled))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("EXPENSE_NOT_REQUESTER"));
            mvc.perform(chama.chair().get("/api/v1/expenses/summary"))
                    .andExpect(jsonPath("$.data.awaitingPaymentMinor").value(KES_3000));
            mvc.perform(chama.treasurer().post("/api/v1/expenses/{id}/cancel", cancelled))
                    .andExpect(jsonPath("$.data.status").value("CANCELLED"));
            mvc.perform(chama.chair().get("/api/v1/expenses?status=REJECTED"))
                    .andExpect(jsonPath("$.data.length()").value(1));
        }
    }

    @Nested
    class MakerChecker {

        @Test
        void whoeverRecordsAnExpenseCannotApproveOrRejectIt() throws Exception {
            Officials chama = chama("Kamau", 1);
            UUID expenseId = idOf(record(chama.chair(), KES_3000, "Hall", "Hire", null));

            mvc.perform(chama.chair().post("/api/v1/expenses/{id}/approve", expenseId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("EXPENSE_SELF_APPROVAL"));
            mvc.perform(chama.chair().post("/api/v1/expenses/{id}/reject", "{\"reason\":\"Changed my mind\"}", expenseId))
                    .andExpect(jsonPath("$.error.code").value("EXPENSE_SELF_APPROVAL"));
            pay(chama.treasurer(), expenseId, "CASH", null)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("EXPENSE_WRONG_STATUS"));
            mvc.perform(chama.secretary().post("/api/v1/expenses/{id}/approve", expenseId))
                    .andExpect(jsonPath("$.data.status").value("APPROVED"));
        }

        @Test
        void theTreasurerRecordsButCannotApprove() throws Exception {
            Officials chama = chama("Otieno", 1);
            UUID expenseId = idOf(record(chama.treasurer(), KES_3000, "Hall", "Hire", null));
            Account member = accounts.member(chama.chama(), "Brian");

            mvc.perform(chama.treasurer().post("/api/v1/expenses/{id}/approve", expenseId)).andExpect(status().isForbidden());
            record(member, KES_3000, "Me", "Transport", null).andExpect(status().isForbidden());
            mvc.perform(member.get("/api/v1/expenses")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].payee").value("Hall"));
            mvc.perform(member.get("/api/v1/expenses/{id}", expenseId)).andExpect(status().isOk());
        }
    }

    @Nested
    class AccountsAndMoney {

        @Test
        void expensesAreChargedToExpenseAccountsOnly() throws Exception {
            Officials chama = chama("Mwangi", 1);

            record(chama.treasurer(), KES_3000, "Hall", "Hire", "1000")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("EXPENSE_ACCOUNT_INVALID"))
                    .andExpect(jsonPath("$.message").value(containsString("not an expense account")));
            record(chama.treasurer(), KES_3000, "Hall", "Hire", "5999")
                    .andExpect(jsonPath("$.error.code").value("EXPENSE_ACCOUNT_INVALID"));

            mvc.perform(chama.chair().post("/api/v1/ledger/accounts",
                    "{\"code\":\"5200\",\"name\":\"Meeting costs\",\"type\":\"EXPENSE\"}")).andExpect(status().isCreated());
            record(chama.treasurer(), KES_3000, "Hall", "Hire", "5200")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.accountName").value("Meeting costs"));
        }

        @Test
        void anExpenseCannotBePaidOutOfAnEmptyAccountOrWithAUsedReference() throws Exception {
            Officials chama = chama("Wekesa", 1);
            UUID first = approved(chama, KES_3000);
            UUID second = approved(chama, KES_3000);

            pay(chama.treasurer(), first, "CASH", null)
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("EXPENSE_INSUFFICIENT_FUNDS"))
                    .andExpect(jsonPath("$.message").value(containsString("cash on hand holds only KES 0.00")));
            pay(chama.treasurer(), first, "MPESA", "QKEXP00001").andExpect(status().isOk());
            pay(chama.treasurer(), second, "MPESA", "qkexp00001")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("EXPENSE_DUPLICATE_REFERENCE"));
        }

        @Test
        void theLicenceMustIncludeExpenses() throws Exception {
            Officials chama = chama("Barasa", 1);
            mvc.perform(accounts.platformAdmin().put("/api/v1/platform/chamas/{id}/license",
                    "{\"features\":[\"CONTRIBUTIONS\"]}", chama.chama().id())).andExpect(status().isOk());

            mvc.perform(chama.chair().get("/api/v1/expenses"))
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

    /** A chama with the given approval quorum, a treasurer, a secretary and KES 20,000 in M-Pesa. */
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
                                .formatted(saver.userId(), KES_20000, UUID.randomUUID().toString().substring(0, 8))))
                .andExpect(status().isCreated());
        return new Officials(chama, treasurer, secretary);
    }

    private Account official(TestChama chama, String firstName, String role) throws Exception {
        Account account = accounts.member(chama, firstName);
        mvc.perform(chama.chair().put("/api/v1/members/{m}/roles", "{\"roles\":[\"%s\"]}".formatted(role),
                accounts.membershipId(account))).andExpect(status().isOk());
        return account;
    }

    /** An expense recorded by the treasurer and approved (quorum 1) by the secretary. */
    UUID approved(Officials chama, long amount) throws Exception {
        UUID id = idOf(record(chama.treasurer(), amount, "Hall", "Hire", null));
        mvc.perform(chama.secretary().post("/api/v1/expenses/{id}/approve", id))
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
        return id;
    }

    ResultActions record(Account official, long amount, String payee, String description, String accountCode) throws Exception {
        return mvc.perform(official.post("/api/v1/expenses", """
                {"amountMinor":%d,"payee":"%s","description":"%s"%s}""".formatted(amount, payee, description,
                accountCode == null ? "" : ",\"accountCode\":\"" + accountCode + "\"")));
    }

    ResultActions pay(Account official, UUID expenseId, String method, String reference) throws Exception {
        return mvc.perform(official.post("/api/v1/expenses/{id}/pay", """
                {"method":"%s"%s}""".formatted(method, reference == null ? "" : ",\"reference\":\"" + reference + "\""),
                expenseId));
    }

    static UUID idOf(ResultActions result) throws Exception {
        return UUID.fromString(JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.data.id"));
    }
}
