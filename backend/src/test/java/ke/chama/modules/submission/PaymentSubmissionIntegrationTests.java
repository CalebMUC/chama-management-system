package ke.chama.modules.submission;

import com.jayway.jsonpath.JsonPath;
import ke.chama.IntegrationTest;
import ke.chama.RecordingEmailSender;
import ke.chama.TestAccounts;
import ke.chama.TestAccounts.Account;
import ke.chama.TestAccounts.TestChama;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Members report their own contributions, penalty payments and loan repayments; nothing is
 * recorded until the treasurer approves the report.
 */
@IntegrationTest
class PaymentSubmissionIntegrationTests {

    static final long KES_5000 = 500_000;
    static final long KES_10000 = 1_000_000;
    static final long FINE = 50_000;

    @Autowired
    MockMvc mvc;

    @Autowired
    RecordingEmailSender emails;

    TestAccounts accounts;

    @BeforeEach
    void setUp() {
        accounts = new TestAccounts(mvc, emails);
    }

    @Test
    void aReportedContributionIsRecordedOnlyOnceTheTreasurerApprovesIt() throws Exception {
        Officials chama = chama("Achieng");
        Account member = accounts.member(chama.chama(), "Kevin");
        String code = reference();

        UUID id = idOf(report(member, "CONTRIBUTION", null, KES_5000, "MPESA", code)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.memberName").value("Kevin Test")));

        // Reported, not recorded.
        mvc.perform(member.get("/api/v1/dashboard/me"))
                .andExpect(jsonPath("$.data.contributions.totalPaidMinor").value(0))
                .andExpect(jsonPath("$.data.submissions.pending").value(1));
        mvc.perform(chama.chair().get("/api/v1/dashboard"))
                .andExpect(jsonPath("$.data.pending.contributionsAwaitingReview").value(1));
        mvc.perform(member.get("/api/v1/payment-submissions/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(id.toString()));

        mvc.perform(chama.treasurer().get("/api/v1/payment-submissions?status=PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(id.toString()))
                .andExpect(jsonPath("$.data[0].reference").value(code));
        mvc.perform(chama.treasurer().post("/api/v1/payment-submissions/{id}/approve", "{\"note\":\"On the statement\"}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.reviewerName").value("Tom Test"));

        mvc.perform(member.get("/api/v1/dashboard/me"))
                .andExpect(jsonPath("$.data.contributions.totalPaidMinor").value(KES_5000))
                .andExpect(jsonPath("$.data.submissions.pending").value(0));
        mvc.perform(chama.treasurer().post("/api/v1/payment-submissions/{id}/approve", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SUBMISSION_WRONG_STATUS"));
        // The same M-Pesa code cannot be reported again.
        report(member, "CONTRIBUTION", null, KES_5000, "MPESA", code)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SUBMISSION_DUPLICATE_REFERENCE"));
    }

    @Test
    void aReportedPenaltyPaymentClearsThePenaltyOnApproval() throws Exception {
        Officials chama = chama("Wanjiru");
        Account member = accounts.member(chama.chama(), "Brian");
        mvc.perform(chama.chair().post("/api/v1/penalties/fines", """
                        {"memberUserId":"%s","amountMinor":%d,"reason":"Missed the meeting"}""".formatted(member.userId(), FINE)))
                .andExpect(status().isCreated());
        UUID penaltyId = UUID.fromString(JsonPath.read(body(member.get("/api/v1/penalties/me")), "$.data.penalties[0].id"));

        report(member, "PENALTY_PAYMENT", penaltyId, FINE + 1, "MPESA", reference())
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.error.code").value("SUBMISSION_TARGET_INVALID"));
        report(member, "PENALTY_PAYMENT", null, FINE, "MPESA", reference())
                .andExpect(status().isBadRequest());

        UUID id = idOf(report(member, "PENALTY_PAYMENT", penaltyId, FINE, "MPESA", reference())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.targetDescription").isString()));
        mvc.perform(member.get("/api/v1/dashboard/me")).andExpect(jsonPath("$.data.penaltiesOutstandingMinor").value(FINE));

        mvc.perform(chama.treasurer().post("/api/v1/payment-submissions/{id}/approve", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
        mvc.perform(member.get("/api/v1/dashboard/me")).andExpect(jsonPath("$.data.penaltiesOutstandingMinor").value(0));
        emails.awaitEmail(member.email(), s -> s.startsWith("Payment confirmed"));
    }

    @Test
    void aReportedLoanRepaymentReducesTheLoanOnApproval() throws Exception {
        Officials chama = chama("Otieno");
        Account member = accounts.member(chama.chama(), "Grace");
        mvc.perform(chama.chair().post("/api/v1/contributions", """
                        {"memberUserId":"%s","amountMinor":%d,"method":"MPESA","reference":"%s"}"""
                        .formatted(member.userId(), 2 * KES_10000, reference())))
                .andExpect(status().isCreated());
        UUID loanId = UUID.fromString(JsonPath.read(body(member.post("/api/v1/loans", """
                {"principalMinor":%d,"termMonths":2,"purpose":"School fees"}""".formatted(KES_10000))), "$.data.loan.id"));

        // Not active yet: nothing to repay.
        report(member, "LOAN_REPAYMENT", loanId, KES_5000, "MPESA", reference())
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.error.code").value("SUBMISSION_TARGET_INVALID"));

        mvc.perform(chama.chair().post("/api/v1/loans/{id}/approve", loanId)).andExpect(status().isOk());
        mvc.perform(chama.treasurer().post("/api/v1/loans/{id}/disburse",
                        "{\"method\":\"MPESA\",\"reference\":\"%s\"}".formatted(reference()), loanId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.loan.status").value("ACTIVE"));

        UUID id = idOf(report(member, "LOAN_REPAYMENT", loanId, KES_5000, "MPESA", reference())
                .andExpect(status().isCreated()));
        mvc.perform(chama.treasurer().post("/api/v1/payment-submissions/{id}/approve", id))
                .andExpect(status().isOk());
        // KES 10,000 at 10% flat = KES 11,000 due; KES 5,000 repaid.
        mvc.perform(member.get("/api/v1/dashboard/me"))
                .andExpect(jsonPath("$.data.loans.outstandingMinor").value(1_100_000 - KES_5000));
    }

    @Test
    void aRejectionNeedsAReasonAndAMemberCanWithdrawTheirOwnReport() throws Exception {
        Officials chama = chama("Mutua");
        Account member = accounts.member(chama.chama(), "Faith");
        UUID wrong = idOf(report(member, "CONTRIBUTION", null, KES_5000, "MPESA", reference()));

        mvc.perform(chama.treasurer().post("/api/v1/payment-submissions/{id}/reject", "{\"reason\":\" \"}", wrong))
                .andExpect(status().isBadRequest());
        mvc.perform(chama.treasurer().post("/api/v1/payment-submissions/{id}/reject",
                        "{\"reason\":\"Not on the M-Pesa statement\"}", wrong))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.reviewNote").value("Not on the M-Pesa statement"));
        emails.awaitEmail(member.email(), s -> s.startsWith("Payment not confirmed"));
        mvc.perform(member.get("/api/v1/dashboard/me")).andExpect(jsonPath("$.data.contributions.totalPaidMinor").value(0));

        UUID typo = idOf(report(member, "CONTRIBUTION", null, KES_5000, "CASH", null));
        Account other = accounts.member(chama.chama(), "Peter");
        mvc.perform(other.post("/api/v1/payment-submissions/{id}/cancel", typo))
                .andExpect(status().isForbidden());
        mvc.perform(other.get("/api/v1/payment-submissions/{id}", typo))
                .andExpect(status().isForbidden());
        mvc.perform(member.post("/api/v1/payment-submissions/{id}/cancel", typo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        mvc.perform(chama.treasurer().post("/api/v1/payment-submissions/{id}/approve", typo))
                .andExpect(status().isConflict());
    }

    @Test
    void onlyOfficialsReviewAndNobodyReviewsTheirOwnReport() throws Exception {
        Officials chama = chama("Kamau");
        Account member = accounts.member(chama.chama(), "Joy");
        UUID id = idOf(report(member, "CONTRIBUTION", null, KES_5000, "MPESA", reference()));

        mvc.perform(member.get("/api/v1/payment-submissions")).andExpect(status().isForbidden());
        mvc.perform(member.post("/api/v1/payment-submissions/{id}/approve", id)).andExpect(status().isForbidden());

        mvc.perform(chama.treasurer().get("/api/v1/payment-submissions/reviewable-kinds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3));

        // The treasurer's own payment goes to another official.
        UUID own = idOf(report(chama.treasurer(), "CONTRIBUTION", null, KES_5000, "MPESA", reference()));
        mvc.perform(chama.treasurer().post("/api/v1/payment-submissions/{id}/approve", own))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("SUBMISSION_SELF_REVIEW"));
        mvc.perform(chama.chair().post("/api/v1/payment-submissions/{id}/approve", own))
                .andExpect(status().isOk());
    }

    // ---- helpers --------------------------------------------------------------------------------

    record Officials(TestChama chama, Account treasurer) {
        Account chair() {
            return chama.chair();
        }
    }

    /** Lends to new members at 10% flat with a quorum of one, and has a treasurer. */
    Officials chama(String chair) throws Exception {
        TestChama chama = accounts.onboard(chair);
        mvc.perform(chama.chair().put("/api/v1/chama/settings", """
                        {"contributionAmountMinor":%d,"contributionFrequency":"MONTHLY","contributionDueDay":28,
                         "lateGraceDays":5,"latePenaltyBasisPoints":0,"loanMaxMultiplier":3,"loanInterestBasisPoints":1000,
                         "loanMinMembershipMonths":0,"loanGuarantorThresholdMinor":100000000,"loanGuarantorsRequired":2,
                         "approvalQuorum":1}""".formatted(KES_5000)))
                .andExpect(status().isOk());
        Account treasurer = accounts.member(chama, "Tom");
        mvc.perform(chama.chair().put("/api/v1/members/{m}/roles", "{\"roles\":[\"TREASURER\"]}",
                accounts.membershipId(treasurer))).andExpect(status().isOk());
        return new Officials(chama, treasurer);
    }

    ResultActions report(Account member, String kind, UUID targetId, long amount, String method, String reference)
            throws Exception {
        return mvc.perform(member.post("/api/v1/payment-submissions", """
                {"kind":"%s"%s,"amountMinor":%d,"method":"%s"%s}""".formatted(kind,
                targetId == null ? "" : ",\"targetId\":\"" + targetId + "\"", amount, method,
                reference == null ? "" : ",\"reference\":\"" + reference + "\"")));
    }

    String body(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse().getContentAsString();
    }

    static UUID idOf(ResultActions result) throws Exception {
        return UUID.fromString(JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.data.id"));
    }

    static String reference() {
        return "S" + Long.toString(System.nanoTime(), 36).toUpperCase();
    }
}
