package ke.chama.modules.penalty.infrastructure.scheduling;

import ke.chama.IntegrationTest;
import ke.chama.RecordingEmailSender;
import ke.chama.TestAccounts;
import ke.chama.TestAccounts.Account;
import ke.chama.TestAccounts.TestChama;
import ke.chama.TestcontainersConfiguration.OwnerJdbc;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The nightly job visits every chama, as that chama, and respects each one's licence. */
@IntegrationTest
class LatePenaltyJobIntegrationTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    RecordingEmailSender emails;

    @Autowired
    OwnerJdbc owner;

    @Autowired
    LatePenaltyJob job;

    @Test
    void penalisesLateMembersInEveryLicensedChama() throws Exception {
        TestAccounts accounts = new TestAccounts(mvc, emails);
        TestChama licensed = chamaWithALateMonth(accounts, "Mokica");
        TestChama unlicensed = chamaWithALateMonth(accounts, "Jamii");
        Account late = owingMember(accounts, licensed, "Kevin");
        Account alsoLate = owingMember(accounts, unlicensed, "Brian");
        mvc.perform(accounts.platformAdmin().put("/api/v1/platform/chamas/{id}/license",
                "{\"features\":[\"CONTRIBUTIONS\"]}", unlicensed.id())).andExpect(status().isOk());

        job.assessAllChamas();

        assertThat(penaltyCount(late)).isEqualTo(1);
        assertThat(penaltyCount(alsoLate)).as("no PENALTIES feature, no penalties").isZero();
        job.assessAllChamas();
        assertThat(penaltyCount(late)).as("each late period is penalised once").isEqualTo(1);
    }

    private int penaltyCount(Account member) {
        return owner.jdbc().queryForObject("select count(*) from penalty.penalties where member_user_id = ?",
                Integer.class, member.userId());
    }

    private TestChama chamaWithALateMonth(TestAccounts accounts, String chair) throws Exception {
        TestChama chama = accounts.onboard(chair);
        mvc.perform(chama.chair().put("/api/v1/chama/settings", """
                        {"contributionAmountMinor":500000,"contributionFrequency":"MONTHLY","contributionDueDay":28,
                         "lateGraceDays":5,"latePenaltyBasisPoints":200,"loanMaxMultiplier":3,"loanInterestBasisPoints":1000,
                         "loanMinMembershipMonths":3,"loanGuarantorThresholdMinor":5000000,"loanGuarantorsRequired":2,
                         "approvalQuorum":2}""")).andExpect(status().isOk());
        owner.jdbc().update("""
                insert into contribution.periods (id, tenant_id, period_key, frequency, start_date, end_date, due_date,
                                                  amount_due_minor, currency, opened_at)
                select gen_random_uuid(), ?, to_char(m, 'YYYY-MM'), 'MONTHLY', m, (m + interval '1 month - 1 day')::date, m,
                       500000, 'KES', now()
                  from (select (date_trunc('month', timezone('Africa/Nairobi', now())) - interval '1 month')::date as m) last_month""",
                chama.id());
        return chama;
    }

    private Account owingMember(TestAccounts accounts, TestChama chama, String firstName) throws Exception {
        Account member = accounts.member(chama, firstName);
        owner.jdbc().update("update member.memberships set joined_at = now() - interval '90 days' where user_id = ?",
                member.userId());
        return member;
    }
}
