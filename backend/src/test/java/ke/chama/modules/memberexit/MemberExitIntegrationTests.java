package ke.chama.modules.memberexit;

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
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Members leaving the chama over HTTP. Each chama has a treasurer (starts and pays out exits) and a
 * secretary, who approves with the chairperson (quorum of two). Loans are 10% flat; loans above
 * KES 15,000 need one guarantor.
 */
@IntegrationTest
class MemberExitIntegrationTests {

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
    class Settlement {

        @Test
        void loansAndPenaltiesComeOffRefundableSavingsSharesStayAndTheRestIsPaidOut() throws Exception {
            Officials chama = chama("Achieng");
            Account kevin = saver(chama, "Kevin", KES_20000);
            UUID shares = UUID.fromString(JsonPath.read(mvc.perform(chama.chair().post("/api/v1/savings-products", """
                            {"code":"SHR","name":"Share capital","kind":"SHARE_CAPITAL","contributionMinor":50000,
                             "mandatory":true}""")).andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString(), "$.data.id"));
            mvc.perform(chama.chair().post("/api/v1/contributions", """
                            {"memberUserId":"%s","amountMinor":100000,"method":"CASH",
                             "allocations":[{"productId":"%s","amountMinor":100000}]}""".formatted(kevin.userId(), shares)))
                    .andExpect(status().isCreated());
            UUID loanId = activeLoan(chama, kevin, KES_10000, List.of());
            UUID fineId = UUID.fromString(JsonPath.read(mvc.perform(chama.chair().post("/api/v1/penalties/fines",
                            "{\"memberUserId\":\"%s\",\"amountMinor\":50000,\"reason\":\"Missed the AGM\"}"
                                    .formatted(kevin.userId()))).andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString(), "$.data.id"));

            // KES 20,000 deposits − 11,000 loan − 500 fine = 8,500 paid out; KES 1,000 shares kept.
            mvc.perform(chama.treasurer().get("/api/v1/member-exits/preview?memberUserId={id}", kevin.userId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.canExit").value(true))
                    .andExpect(jsonPath("$.data.blockers.length()").value(0))
                    .andExpect(jsonPath("$.data.settlement.refundableMinor").value(2_000_000))
                    .andExpect(jsonPath("$.data.settlement.forfeitedMinor").value(100_000))
                    .andExpect(jsonPath("$.data.settlement.loansMinor").value(1_100_000))
                    .andExpect(jsonPath("$.data.settlement.penaltiesMinor").value(50_000))
                    .andExpect(jsonPath("$.data.settlement.payoutMinor").value(850_000))
                    .andExpect(jsonPath("$.data.settlement.dividendMinor").value(0))
                    .andExpect(jsonPath("$.data.settlement.lines.length()").value(4));

            UUID exitId = exitId(start(chama.treasurer(), kevin, "Relocating to Mombasa")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.status").value("PENDING_APPROVAL"))
                    .andExpect(jsonPath("$.data.requiredApprovals").value(2))
                    .andExpect(jsonPath("$.data.memberName").value("Kevin Test")));

            mvc.perform(chama.treasurer().post("/api/v1/member-exits/{id}/approve", exitId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
            mvc.perform(kevin.post("/api/v1/member-exits/{id}/approve", exitId)).andExpect(status().isForbidden());
            mvc.perform(chama.treasurer().post("/api/v1/member-exits/{id}/settle", "{\"method\":\"CASH\"}", exitId))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("EXIT_WRONG_STATUS"));
            mvc.perform(chama.chair().post("/api/v1/member-exits/{id}/approve", "{\"note\":\"Agreed\"}", exitId))
                    .andExpect(jsonPath("$.message").value("Approval recorded (1 of 2)"));
            mvc.perform(chama.secretary().post("/api/v1/member-exits/{id}/approve", exitId))
                    .andExpect(jsonPath("$.message").value("Exit approved"))
                    .andExpect(jsonPath("$.data.status").value("APPROVED"));
            mvc.perform(kevin.get("/api/v1/member-exits/mine"))
                    .andExpect(jsonPath("$.data[0].status").value("APPROVED"))
                    .andExpect(jsonPath("$.data[0].settlement.payoutMinor").value(850_000));

            mvc.perform(chama.treasurer().post("/api/v1/member-exits/{id}/settle",
                            "{\"method\":\"MPESA\",\"reference\":\"qkexit0001\"}", exitId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("SETTLED"))
                    .andExpect(jsonPath("$.data.paymentReference").value("QKEXIT0001"))
                    .andExpect(jsonPath("$.data.settlementEntryId").isNotEmpty());

            mvc.perform(chama.treasurer().get("/api/v1/loans/{id}", loanId))
                    .andExpect(jsonPath("$.data.loan.status").value("REPAID"))
                    .andExpect(jsonPath("$.data.repayments[0].method").value("SAVINGS"));
            mvc.perform(chama.treasurer().get("/api/v1/penalties/{id}", fineId))
                    .andExpect(jsonPath("$.data.penalty.status").value("PAID"));
            mvc.perform(chama.chair().get("/api/v1/ledger/accounts"))
                    .andExpect(jsonPath("$.data[?(@.code=='1020')].balanceMinor").value(150_000))
                    .andExpect(jsonPath("$.data[?(@.code=='1000')].balanceMinor").value(100_000))
                    .andExpect(jsonPath("$.data[?(@.code=='1100')].balanceMinor").value(0))
                    .andExpect(jsonPath("$.data[?(@.code=='2000')].balanceMinor").value(0))
                    .andExpect(jsonPath("$.data[?(@.code=='3000')].balanceMinor").value(100_000))
                    .andExpect(jsonPath("$.data[?(@.code=='3100')].balanceMinor").value(0));
            mvc.perform(chama.treasurer().get("/api/v1/member-accounts?memberUserId={id}", kevin.userId()))
                    .andExpect(jsonPath("$.data.length()").value(2))
                    .andExpect(jsonPath("$.data[?(@.status=='ACTIVE')]").isEmpty());
            mvc.perform(kevin.get("/api/v1/members/me"))
                    .andExpect(jsonPath("$.data.membership.status").value("EXITED"))
                    .andExpect(jsonPath("$.data.membership.exitReason").value("Relocating to Mombasa"))
                    .andExpect(jsonPath("$.data.permissions.length()").value(0));
            mvc.perform(chama.chair().get("/api/v1/members?status=EXITED"))
                    .andExpect(jsonPath("$.data[0].userId").value(kevin.userId().toString()));

            String log = mvc.perform(chama.chair().get("/api/v1/audit-log?size=200")).andReturn().getResponse()
                    .getContentAsString();
            List<String> actions = JsonPath.read(log, "$.data[*].action");
            assertThat(actions).contains("MEMBER_EXIT_REQUESTED", "MEMBER_EXIT_APPROVED", "MEMBER_EXIT_SETTLED",
                    "MEMBER_EXITED", "MEMBER_ACCOUNT_CLOSED");
        }

        @Test
        void aSettlementThatChangedAfterApprovalIsNotPaidOut() throws Exception {
            Officials chama = chama("Wanjiru");
            Account member = saver(chama, "Paul", KES_10000);
            UUID exitId = approvedExit(chama, member);

            contribute(chama, member, 200_000, "QKMORE" + UUID.randomUUID().toString().substring(0, 4));

            mvc.perform(chama.treasurer().post("/api/v1/member-exits/{id}/settle", "{\"method\":\"CASH\"}", exitId))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("EXIT_SETTLEMENT_CHANGED"));
            mvc.perform(member.get("/api/v1/members/me")).andExpect(jsonPath("$.data.membership.status").value("ACTIVE"));
        }

        @Test
        void aMemberWithNothingToTakeLeavesWithoutAPayout() throws Exception {
            Officials chama = chama("Otieno");
            Account member = accounts.member(chama.chama(), "Faith");
            UUID exitId = approvedExit(chama, member);

            mvc.perform(chama.treasurer().post("/api/v1/member-exits/{id}/settle", exitId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("SETTLED"))
                    .andExpect(jsonPath("$.data.paymentMethod").doesNotExist());
            mvc.perform(member.get("/api/v1/members/me")).andExpect(jsonPath("$.data.membership.status").value("EXITED"));
        }

        @Test
        void thePayoutNeedsEnoughMoneyInTheAccountItLeaves() throws Exception {
            Officials chama = chama("Mwangi");
            Account member = saver(chama, "Esther", KES_10000);
            UUID exitId = approvedExit(chama, member);

            mvc.perform(chama.treasurer().post("/api/v1/member-exits/{id}/settle",
                            "{\"method\":\"BANK\",\"reference\":\"SLIP-1\"}", exitId))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("EXIT_INSUFFICIENT_FUNDS"));
        }
    }

    @Nested
    class Blockers {

        @Test
        void aGuarantorLeavesOnlyAfterTheGuaranteeIsTransferredToAnotherMember() throws Exception {
            Officials chama = chama("Kamau");
            Account gloria = saver(chama, "Gloria", KES_10000);
            Account henry = saver(chama, "Henry", KES_10000);
            Account brian = saver(chama, "Brian", KES_20000);
            Account stranger = accounts.member(chama.chama(), "Stella");
            UUID loanId = activeLoan(chama, brian, KES_20000, List.of(gloria));

            mvc.perform(chama.treasurer().get("/api/v1/member-exits/preview?memberUserId={id}", gloria.userId()))
                    .andExpect(jsonPath("$.data.canExit").value(false))
                    .andExpect(jsonPath("$.data.blockers[0]", containsString("transfer the guarantee")));
            start(chama.treasurer(), gloria, "Moving abroad")
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("EXIT_BLOCKED"));

            String replace = "{\"newGuarantorUserId\":\"%s\"}".formatted(henry.userId());
            mvc.perform(stranger.post("/api/v1/loans/{l}/guarantors/{g}/replace", replace, loanId, gloria.userId()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("LOAN_GUARANTEE_TRANSFER_NOT_ALLOWED"));
            mvc.perform(gloria.post("/api/v1/loans/{l}/guarantors/{g}/replace", replace, loanId, gloria.userId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.guarantors[?(@.userId=='%s')].status".formatted(henry.userId()))
                            .value(hasItem("REQUESTED")))
                    .andExpect(jsonPath("$.data.guarantors[?(@.userId=='%s')].replacesUserId".formatted(henry.userId()))
                            .value(hasItem(gloria.userId().toString())));
            mvc.perform(chama.treasurer().get("/api/v1/member-exits/preview?memberUserId={id}", gloria.userId()))
                    .andExpect(jsonPath("$.data.blockers[0]", containsString("has not answered yet")));

            mvc.perform(henry.post("/api/v1/loans/{id}/guarantee", "{\"accept\":true}", loanId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.guarantors[?(@.userId=='%s')].status".formatted(gloria.userId()))
                            .value(hasItem("RELEASED")));

            mvc.perform(chama.treasurer().get("/api/v1/member-exits/preview?memberUserId={id}", gloria.userId()))
                    .andExpect(jsonPath("$.data.canExit").value(true));
            start(chama.treasurer(), gloria, "Moving abroad").andExpect(status().isCreated());
            start(chama.treasurer(), gloria, "Moving abroad")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("EXIT_IN_PROGRESS"));
            mvc.perform(chama.treasurer().get("/api/v1/member-exits/preview?memberUserId={id}", henry.userId()))
                    .andExpect(jsonPath("$.data.canExit").value(false));
        }

        @Test
        void owingMoreThanRefundableSavingsBlocksTheExit() throws Exception {
            Officials chama = chama("Njeri");
            Account member = saver(chama, "Daniel", 100_000);
            mvc.perform(chama.chair().post("/api/v1/penalties/fines",
                            "{\"memberUserId\":\"%s\",\"amountMinor\":500000,\"reason\":\"Damaged chama property\"}"
                                    .formatted(member.userId())))
                    .andExpect(status().isCreated());

            mvc.perform(chama.treasurer().get("/api/v1/member-exits/preview?memberUserId={id}", member.userId()))
                    .andExpect(jsonPath("$.data.settlement.shortfallMinor").value(400_000))
                    .andExpect(jsonPath("$.data.blockers[0]", containsString("KES 4,000.00 more than")));
        }

        @Test
        void theOnlyChairpersonCannotLeaveAndNobodyStartsTheirOwnExit() throws Exception {
            Officials chama = chama("Chebet");

            mvc.perform(chama.treasurer().get("/api/v1/member-exits/preview?memberUserId={id}", chama.chair().userId()))
                    .andExpect(jsonPath("$.data.blockers[0]", containsString("only active chairperson")));
            start(chama.treasurer(), chama.treasurer(), "Retiring")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("EXIT_SELF_HANDLING"));
        }

        @Test
        void whoeverStartsAnExitCannotApproveIt() throws Exception {
            Officials chama = chama("Wafula");
            Account member = accounts.member(chama.chama(), "Ann");
            UUID exitId = exitId(start(chama.chair(), member, "Leaving").andExpect(status().isCreated()));

            mvc.perform(chama.chair().post("/api/v1/member-exits/{id}/approve", exitId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("EXIT_SELF_HANDLING"));
            mvc.perform(chama.chair().post("/api/v1/member-exits/{id}/cancel", exitId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    record Officials(TestChama chama, Account treasurer, Account secretary) {
        Account chair() {
            return chama.chair();
        }
    }

    /** A chama with the standard loan rules, a quorum of two, a treasurer and a secretary. */
    Officials chama(String chair) throws Exception {
        TestChama chama = accounts.onboard(chair);
        mvc.perform(chama.chair().put("/api/v1/chama/settings", """
                        {"contributionAmountMinor":500000,"contributionFrequency":"MONTHLY","contributionDueDay":28,
                         "lateGraceDays":5,"latePenaltyBasisPoints":0,"loanMaxMultiplier":3,"loanInterestBasisPoints":1000,
                         "loanMinMembershipMonths":3,"loanGuarantorThresholdMinor":1500000,"loanGuarantorsRequired":1,
                         "approvalQuorum":2}"""))
                .andExpect(status().isOk());
        Account treasurer = official(chama, "Tom", "TREASURER");
        Account secretary = official(chama, "Sally", "SECRETARY");
        return new Officials(chama, treasurer, secretary);
    }

    Account official(TestChama chama, String firstName, String role) throws Exception {
        Account official = accounts.member(chama, firstName);
        mvc.perform(chama.chair().put("/api/v1/members/{m}/roles", "{\"roles\":[\"%s\"]}".formatted(role),
                accounts.membershipId(official))).andExpect(status().isOk());
        return official;
    }

    /** A member of four months with {@code savings} contributed by M-Pesa. */
    Account saver(Officials chama, String firstName, long savings) throws Exception {
        Account member = accounts.member(chama.chama(), firstName);
        owner.jdbc().update("update member.memberships set joined_at = now() - interval '125 days' where user_id = ?",
                member.userId());
        contribute(chama, member, savings, "QK" + UUID.randomUUID().toString().substring(0, 8));
        return member;
    }

    void contribute(Officials chama, Account member, long amount, String reference) throws Exception {
        mvc.perform(chama.chair().post("/api/v1/contributions",
                        "{\"memberUserId\":\"%s\",\"amountMinor\":%d,\"method\":\"MPESA\",\"reference\":\"%s\"}"
                                .formatted(member.userId(), amount, reference)))
                .andExpect(status().isCreated());
    }

    /** Applied for over 2 months, guaranteed, approved by the chair and treasurer, and paid out by M-Pesa. */
    UUID activeLoan(Officials chama, Account borrower, long principal, List<Account> guarantors) throws Exception {
        String ids = String.join(",", guarantors.stream().map(g -> "\"" + g.userId() + "\"").toList());
        UUID loanId = UUID.fromString(JsonPath.read(mvc.perform(borrower.post("/api/v1/loans", """
                        {"principalMinor":%d,"termMonths":2,"purpose":"Stock","guarantorUserIds":[%s]}"""
                        .formatted(principal, ids))).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.data.loan.id"));
        for (Account g : guarantors) {
            mvc.perform(g.post("/api/v1/loans/{id}/guarantee", "{\"accept\":true}", loanId)).andExpect(status().isOk());
        }
        mvc.perform(chama.chair().post("/api/v1/loans/{id}/approve", loanId)).andExpect(status().isOk());
        mvc.perform(chama.treasurer().post("/api/v1/loans/{id}/approve", loanId)).andExpect(status().isOk());
        mvc.perform(chama.treasurer().post("/api/v1/loans/{id}/disburse",
                        "{\"method\":\"MPESA\",\"reference\":\"QKOUT%s\"}".formatted(UUID.randomUUID().toString().substring(0, 5)),
                        loanId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.loan.status").value("ACTIVE"));
        return loanId;
    }

    ResultActions start(Account official, Account member, String reason) throws Exception {
        return mvc.perform(official.post("/api/v1/member-exits",
                "{\"memberUserId\":\"%s\",\"reason\":\"%s\"}".formatted(member.userId(), reason)));
    }

    UUID approvedExit(Officials chama, Account member) throws Exception {
        UUID exitId = exitId(start(chama.treasurer(), member, "Leaving").andExpect(status().isCreated()));
        mvc.perform(chama.chair().post("/api/v1/member-exits/{id}/approve", exitId)).andExpect(status().isOk());
        mvc.perform(chama.secretary().post("/api/v1/member-exits/{id}/approve", exitId))
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
        return exitId;
    }

    static UUID exitId(ResultActions result) throws Exception {
        return UUID.fromString(JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.data.id"));
    }
}
