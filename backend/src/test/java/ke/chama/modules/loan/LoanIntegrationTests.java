package ke.chama.modules.loan;

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
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Loans over HTTP. Each chama lends up to 3× savings, at 10% flat, to members of at least three
 * months; loans above KES 50,000 need two guarantors. Members' savings come from M-Pesa
 * contributions, so the chama's money is in its M-Pesa account.
 */
@IntegrationTest
public class LoanIntegrationTests {

    static final long KES_10000 = 1_000_000;
    static final long KES_20000 = 2_000_000;

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
    class Lifecycle {

        @Test
        void aLoanIsApprovedByQuorumDisbursedAndRepaid() throws Exception {
            Officials chama = chama("Achieng", 2);
            Account member = saver(chama, "Kevin", KES_20000);

            UUID loanId = apply(member, KES_10000, 2, "School fees", List.of())
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.loan.status").value("PENDING_APPROVAL"))
                    .andExpect(jsonPath("$.data.loan.interestMinor").value(100_000))
                    .andExpect(jsonPath("$.data.loan.totalDueMinor").value(1_100_000))
                    .andExpect(jsonPath("$.data.loan.requiredApprovals").value(2))
                    .andReturn().getResponse().getContentAsString().transform(LoanIntegrationTests::loanId);

            mvc.perform(chama.chair().post("/api/v1/loans/{id}/approve", "{\"note\":\"Good saver\"}", loanId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Approval recorded (1 of 2)"))
                    .andExpect(jsonPath("$.data.loan.status").value("PENDING_APPROVAL"));
            mvc.perform(chama.treasurer().post("/api/v1/loans/{id}/approve", loanId))
                    .andExpect(jsonPath("$.message").value("Loan approved"))
                    .andExpect(jsonPath("$.data.loan.status").value("APPROVED"))
                    .andExpect(jsonPath("$.data.approvals.length()").value(2));
            emails.awaitEmail(member.email(), s -> s.equals("Your loan of KES 10,000.00 was approved"));

            disburse(chama.treasurer(), loanId, "MPESA", "QKLOANOUT1")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.loan.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.data.loan.outstandingMinor").value(1_100_000))
                    .andExpect(jsonPath("$.data.schedule.length()").value(2))
                    .andExpect(jsonPath("$.data.schedule[0].amountMinor").value(550_000))
                    .andExpect(jsonPath("$.data.disbursement.reference").value("QKLOANOUT1"));
            assertThat(emails.awaitEmail(member.email(), s -> s.endsWith("was disbursed")).body())
                    .contains("first instalment of KES 5,500.00");

            mvc.perform(member.get("/api/v1/ledger/me")).andExpect(jsonPath("$.data.loansOutstandingMinor").value(1_100_000));
            mvc.perform(chama.chair().get("/api/v1/ledger/accounts"))
                    .andExpect(jsonPath("$.data[?(@.code=='1100')].balanceMinor").value(1_100_000))
                    .andExpect(jsonPath("$.data[?(@.code=='4000')].balanceMinor").value(100_000))
                    .andExpect(jsonPath("$.data[?(@.code=='1020')].balanceMinor").value((int) (KES_20000 - KES_10000)));

            repay(chama.treasurer(), loanId, 600_000, "CASH", null)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.message").value("Repayment recorded"))
                    .andExpect(jsonPath("$.data.loan.outstandingMinor").value(500_000))
                    .andExpect(jsonPath("$.data.schedule[0].status").value("PAID"))
                    .andExpect(jsonPath("$.data.schedule[1].status").value("PARTLY_PAID"));
            repay(chama.treasurer(), loanId, 500_000, "MPESA", "QKLOANIN01")
                    .andExpect(jsonPath("$.message").value("Loan fully repaid"))
                    .andExpect(jsonPath("$.data.loan.status").value("REPAID"))
                    .andExpect(jsonPath("$.data.repayments.length()").value(2));
            emails.awaitEmail(member.email(), s -> s.endsWith("is fully repaid"));

            mvc.perform(member.get("/api/v1/ledger/me")).andExpect(jsonPath("$.data.loansOutstandingMinor").value(0));
            mvc.perform(member.get("/api/v1/loans/me"))
                    .andExpect(jsonPath("$.data.outstandingMinor").value(0))
                    .andExpect(jsonPath("$.data.loans[0].loan.status").value("REPAID"));
            String log = mvc.perform(chama.chair().get("/api/v1/audit-log?size=100")).andReturn().getResponse().getContentAsString();
            List<String> actions = JsonPath.read(log, "$.data[?(@.targetType=='LOAN')].action");
            assertThat(actions).contains("LOAN_APPLIED", "LOAN_APPROVAL_GIVEN", "LOAN_APPROVED", "LOAN_DISBURSED",
                    "LOAN_REPAYMENT_RECORDED", "LOAN_REPAID");
        }

        @Test
        void anOfficialRejectsWithAReasonAndTheMemberMayApplyAgain() throws Exception {
            Officials chama = chama("Chebet", 2);
            Account member = saver(chama, "Grace", KES_20000);
            UUID loanId = loanIdOf(apply(member, KES_10000, 6, "Business stock", List.of()));

            mvc.perform(chama.chair().post("/api/v1/loans/{id}/reject", "{\"reason\":\"Clear your arrears first\"}", loanId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.loan.status").value("REJECTED"))
                    .andExpect(jsonPath("$.data.decision.reason").value("Clear your arrears first"));
            assertThat(emails.awaitEmail(member.email(), s -> s.contains("was not approved")).body())
                    .contains("Clear your arrears first");

            apply(member, KES_10000, 6, "Business stock", List.of()).andExpect(status().isCreated());
        }

        @Test
        void theBorrowerCancelsBeforeDisbursement() throws Exception {
            Officials chama = chama("Kamau", 1);
            Account member = saver(chama, "Peter", KES_20000);
            UUID loanId = loanIdOf(apply(member, KES_10000, 6, "Rent", List.of()));

            mvc.perform(chama.treasurer().post("/api/v1/loans/{id}/cancel", loanId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("LOAN_NOT_BORROWER"));
            mvc.perform(member.post("/api/v1/loans/{id}/cancel", loanId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.loan.status").value("CANCELLED"));
            mvc.perform(chama.chair().post("/api/v1/loans/{id}/approve", loanId))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("LOAN_WRONG_STATUS"));
        }
    }

    @Nested
    class Eligibility {

        @Test
        void membersSeeWhatTheyMayBorrow() throws Exception {
            Officials chama = chama("Otieno", 2);
            Account member = saver(chama, "Brian", KES_20000);

            mvc.perform(member.get("/api/v1/loans/eligibility"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.eligible").value(true))
                    .andExpect(jsonPath("$.data.savingsMinor").value(KES_20000))
                    .andExpect(jsonPath("$.data.maxLoanMinor").value(3 * KES_20000))
                    .andExpect(jsonPath("$.data.guarantorThresholdMinor").value(5_000_000))
                    .andExpect(jsonPath("$.data.guarantorsRequired").value(2));

            apply(member, 3 * KES_20000 + 1, 6, "Car", List.of())
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("LOAN_NOT_ELIGIBLE"))
                    .andExpect(jsonPath("$.message").value(containsString("The most you can borrow is KES 60,000.00")));
        }

        @Test
        void newMembersAndMembersWithALoanInProgressCannotBorrow() throws Exception {
            Officials chama = chama("Mwangi", 2);
            Account newcomer = accounts.member(chama.chama(), "Sam");
            contribute(chama, newcomer, KES_20000, "QKNEW00001");

            mvc.perform(newcomer.get("/api/v1/loans/eligibility"))
                    .andExpect(jsonPath("$.data.eligible").value(false))
                    .andExpect(jsonPath("$.data.reasons[0]").value(containsString("after 3 months of membership")));

            Account member = saver(chama, "Lucy", KES_20000);
            apply(member, KES_10000, 6, "Fees", List.of()).andExpect(status().isCreated());
            apply(member, KES_10000, 6, "More fees", List.of())
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.message").value(containsString("already have a loan in progress")));
        }
    }

    @Nested
    class Guarantors {

        @Test
        void bigLoansWaitForEnoughGuarantors() throws Exception {
            Officials chama = chama("Njoroge", 2);
            Account borrower = saver(chama, "Faith", 3_000_000);   // may borrow up to KES 90,000
            Account alice = accounts.member(chama.chama(), "Alice");
            Account bob = accounts.member(chama.chama(), "Bob");
            Account carol = accounts.member(chama.chama(), "Carol");

            apply(borrower, 6_000_000, 12, "Plot", List.of())
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(containsString("needs 2 guarantors")));
            UUID loanId = loanIdOf(apply(borrower, 6_000_000, 12, "Plot", List.of(alice.userId(), bob.userId()))
                    .andExpect(jsonPath("$.data.loan.status").value("PENDING_GUARANTORS")));
            assertThat(emails.awaitEmail(alice.email(), s -> s.contains("asked you to guarantee a loan")).body())
                    .contains("KES 60,000.00 over 12 months");

            mvc.perform(alice.get("/api/v1/loans/guarantees"))
                    .andExpect(jsonPath("$.data[0].myStatus").value("REQUESTED"));
            guarantee(alice, loanId, true).andExpect(status().isOk());
            guarantee(bob, loanId, false)
                    .andExpect(jsonPath("$.data.loan.status").value("PENDING_GUARANTORS"));
            mvc.perform(chama.chair().post("/api/v1/loans/{id}/approve", loanId))
                    .andExpect(status().isConflict());

            mvc.perform(borrower.post("/api/v1/loans/{id}/guarantors",
                            "{\"guarantorUserId\":\"%s\"}".formatted(carol.userId()), loanId))
                    .andExpect(status().isOk());
            guarantee(carol, loanId, true)
                    .andExpect(jsonPath("$.data.loan.status").value("PENDING_APPROVAL"))
                    .andExpect(jsonPath("$.data.loan.acceptedGuarantors").value(2));
            guarantee(carol, loanId, true)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("LOAN_GUARANTOR_RESPONDED"));
        }

        @Test
        void aGuarantorWhoIsAnOfficialCannotApprove() throws Exception {
            Officials chama = chama("Rotich", 1);
            Account borrower = saver(chama, "Joy", 3_000_000);
            Account other = accounts.member(chama.chama(), "Mark");
            UUID loanId = loanIdOf(apply(borrower, 6_000_000, 12, "Plot",
                    List.of(chama.treasurer().userId(), other.userId())));
            guarantee(chama.treasurer(), loanId, true);
            guarantee(other, loanId, true);

            mvc.perform(chama.treasurer().post("/api/v1/loans/{id}/approve", loanId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("LOAN_GUARANTOR_CANNOT_APPROVE"));
            mvc.perform(chama.chair().post("/api/v1/loans/{id}/approve", loanId))
                    .andExpect(jsonPath("$.data.loan.status").value("APPROVED"));
        }
    }

    @Nested
    class Accountability {

        @Test
        void officialsDoNotHandleTheirOwnLoans() throws Exception {
            Officials chama = chama("Atieno", 1);
            Account treasurer = chama.treasurer();
            contribute(chama, treasurer, KES_20000, "QKTREAS001");
            joinedLongAgo(treasurer);
            UUID loanId = loanIdOf(apply(treasurer, KES_10000, 6, "Fees", List.of()));

            mvc.perform(treasurer.post("/api/v1/loans/{id}/approve", loanId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("LOAN_SELF_HANDLING"));
            mvc.perform(chama.chair().post("/api/v1/loans/{id}/approve", loanId)).andExpect(status().isOk());
            disburse(treasurer, loanId, "MPESA", "QKSELF0001")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("LOAN_SELF_HANDLING"));
        }

        @Test
        void aLoanCannotBePaidOutOfAnEmptyAccountOrWithAUsedReference() throws Exception {
            Officials chama = chama("Wekesa", 1);
            Account member = saver(chama, "Ann", KES_20000);
            UUID loanId = loanIdOf(apply(member, KES_10000, 6, "Fees", List.of()));
            mvc.perform(chama.chair().post("/api/v1/loans/{id}/approve", loanId));

            disburse(chama.treasurer(), loanId, "CASH", null)
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("LOAN_INSUFFICIENT_FUNDS"))
                    .andExpect(jsonPath("$.message").value(containsString("cash on hand holds only KES 0.00")));
            disburse(chama.treasurer(), loanId, "MPESA", "QKOUT00001").andExpect(status().isOk());
            repay(chama.treasurer(), loanId, 100_000, "MPESA", "qkout00001")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("LOAN_DUPLICATE_REFERENCE"));
        }

        @Test
        void membersSeeOnlyTheirOwnLoans() throws Exception {
            Officials chama = chama("Kiprop", 2);
            Account member = saver(chama, "Eve", KES_20000);

            mvc.perform(member.get("/api/v1/loans")).andExpect(status().isForbidden());
            mvc.perform(member.get("/api/v1/loans/me")).andExpect(status().isOk());
        }

        @Test
        void theLicenceMustIncludeLoans() throws Exception {
            Officials chama = chama("Barasa", 2);
            mvc.perform(accounts.platformAdmin().put("/api/v1/platform/chamas/{id}/license",
                    "{\"features\":[\"CONTRIBUTIONS\",\"PENALTIES\"]}", chama.chama().id())).andExpect(status().isOk());

            mvc.perform(chama.chair().get("/api/v1/loans"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("LICENSE_FEATURE_NOT_INCLUDED"));
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    record Officials(TestChama chama, Account treasurer) {
        Account chair() {
            return chama.chair();
        }
    }

    /** A chama with the standard loan rules, the given approval quorum and a treasurer. */
    Officials chama(String chair, int quorum) throws Exception {
        TestChama chama = accounts.onboard(chair);
        mvc.perform(chama.chair().put("/api/v1/chama/settings", """
                        {"contributionAmountMinor":500000,"contributionFrequency":"MONTHLY","contributionDueDay":28,
                         "lateGraceDays":5,"latePenaltyBasisPoints":0,"loanMaxMultiplier":3,"loanInterestBasisPoints":1000,
                         "loanMinMembershipMonths":3,"loanGuarantorThresholdMinor":5000000,"loanGuarantorsRequired":2,
                         "approvalQuorum":%d}""".formatted(quorum)))
                .andExpect(status().isOk());
        Account treasurer = accounts.member(chama, "Tom");
        mvc.perform(chama.chair().put("/api/v1/members/{m}/roles", "{\"roles\":[\"TREASURER\"]}",
                accounts.membershipId(treasurer))).andExpect(status().isOk());
        return new Officials(chama, treasurer);
    }

    /** A member of four months with {@code savings} contributed by M-Pesa. */
    Account saver(Officials chama, String firstName, long savings) throws Exception {
        Account member = accounts.member(chama.chama(), firstName);
        joinedLongAgo(member);
        contribute(chama, member, savings, "QK" + UUID.randomUUID().toString().substring(0, 8));
        return member;
    }

    void joinedLongAgo(Account member) {
        owner.jdbc().update("update member.memberships set joined_at = now() - interval '125 days' where user_id = ?",
                member.userId());
    }

    void contribute(Officials chama, Account member, long amount, String reference) throws Exception {
        mvc.perform(chama.chair().post("/api/v1/contributions",
                        "{\"memberUserId\":\"%s\",\"amountMinor\":%d,\"method\":\"MPESA\",\"reference\":\"%s\"}"
                                .formatted(member.userId(), amount, reference)))
                .andExpect(status().isCreated());
    }

    ResultActions apply(Account borrower, long principal, int term, String purpose, List<UUID> guarantors) throws Exception {
        String ids = String.join(",", guarantors.stream().map(id -> "\"" + id + "\"").toList());
        return mvc.perform(borrower.post("/api/v1/loans", """
                {"principalMinor":%d,"termMonths":%d,"purpose":"%s","guarantorUserIds":[%s]}"""
                .formatted(principal, term, purpose, ids)));
    }

    ResultActions guarantee(Account guarantor, UUID loanId, boolean accept) throws Exception {
        return mvc.perform(guarantor.post("/api/v1/loans/{id}/guarantee", "{\"accept\":%s}".formatted(accept), loanId));
    }

    ResultActions disburse(Account official, UUID loanId, String method, String reference) throws Exception {
        return mvc.perform(official.post("/api/v1/loans/{id}/disburse", """
                {"method":"%s"%s}""".formatted(method, reference == null ? "" : ",\"reference\":\"" + reference + "\""),
                loanId));
    }

    ResultActions repay(Account official, UUID loanId, long amount, String method, String reference) throws Exception {
        return mvc.perform(official.post("/api/v1/loans/{id}/repayments", """
                {"amountMinor":%d,"method":"%s"%s}""".formatted(amount, method,
                reference == null ? "" : ",\"reference\":\"" + reference + "\""), loanId));
    }

    static UUID loanIdOf(ResultActions result) throws Exception {
        return loanId(result.andReturn().getResponse().getContentAsString());
    }

    static UUID loanId(String body) {
        return UUID.fromString(JsonPath.read(body, "$.data.loan.id"));
    }
}
