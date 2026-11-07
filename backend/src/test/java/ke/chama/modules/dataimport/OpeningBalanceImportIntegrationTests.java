package ke.chama.modules.dataimport;

import com.jayway.jsonpath.JsonPath;
import ke.chama.IntegrationTest;
import ke.chama.RecordingEmailSender;
import ke.chama.TestAccounts;
import ke.chama.TestAccounts.Account;
import ke.chama.TestAccounts.TestChama;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A chama moving over from Excel: download the template, fill it in, preview it, import it once.
 * Figures in KES: savings 10,000 (the chairperson, already a member) + 20,000 + 15,000 + 5,000;
 * Alice owes 22,000 on a loan, Bob 500 in penalties; cash 2,000, bank 10,000, M-Pesa 3,500; CIC
 * 25,000 (worth 26,500) and an Airbnb unit 30,000.
 */
@IntegrationTest
public class OpeningBalanceImportIntegrationTests {

    static final String URL = "/api/v1/imports/opening-balances";

    @Autowired
    MockMvc mvc;

    @Autowired
    RecordingEmailSender emails;

    TestAccounts accounts;
    LocalDate asOf;

    @BeforeEach
    void setUp() {
        accounts = new TestAccounts(mvc, emails);
        asOf = LocalDate.now(ZoneId.of("Africa/Nairobi")).minusDays(7);
    }

    @Test
    void aChamaMovesItsRecordsOverFromExcel() throws Exception {
        TestChama chama = accounts.onboard("Mokeira");
        String alice = TestAccounts.uniqueEmail("alice");
        String bob = TestAccounts.uniqueEmail("bob");
        String carol = TestAccounts.uniqueEmail("carol");
        byte[] file = Workbooks.from(template(chama.chair()))
                .row("Members", chama.chair().email(), "Mokeira", "Chair", "0700000001", asOf.minusYears(3), null, 10000, null)
                .row("Members", alice, "Alice", "Wanjiku", 712345601, asOf.minusYears(2), "Treasurer", 20000, null)
                .row("Members", bob, "Bob", "Otieno", "0712345602", asOf.minusYears(1), null, 15000, 500)
                .row("Members", carol, "Carol", "Akinyi", "0712345603", asOf.minusMonths(2), null, 5000, null)
                .row("Loans", alice, 30000, 3000, 12, asOf.minusMonths(6), 11000, "School fees")
                .setRow("Accounts", 2, "Cash on hand", 2000)
                .setRow("Accounts", 3, "Bank account", 10000)
                .setRow("Accounts", 4, "M-Pesa", 3500)
                .row("Investments", "CIC money market fund", "MONEY_MARKET_FUND", "CIC", asOf.minusYears(2), 25000, 26500, 11)
                .row("Investments", "Airbnb unit, Kilimani", "BUSINESS", null, asOf.minusMonths(8), 30000, null, null)
                .bytes();

        upload(chama.chair(), URL + "/preview", file)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Ready to import"))
                .andExpect(jsonPath("$.data.importable").value(true))
                .andExpect(jsonPath("$.data.summary.newMembers").value(3))
                .andExpect(jsonPath("$.data.summary.existingMembers").value(1))
                .andExpect(jsonPath("$.data.summary.savingsMinor").value(5_000_000))
                .andExpect(jsonPath("$.data.summary.loansOutstandingMinor").value(2_200_000))
                // 15,500 + 55,000 + 22,000 + 500 - 50,000
                .andExpect(jsonPath("$.data.summary.retainedSurplusMinor").value(4_300_000));
        assertThat(emails.sentTo(alice)).as("a preview saves and sends nothing").isEmpty();

        upload(chama.chair(), URL, file)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.ledgerEntryId").isNotEmpty())
                .andExpect(jsonPath("$.data.summary.investments").value(2));

        // The books, as at the cut-over date.
        mvc.perform(chama.chair().get("/api/v1/ledger/accounts"))
                .andExpect(jsonPath("$.data[?(@.code=='1000')].balanceMinor").value(200_000))
                .andExpect(jsonPath("$.data[?(@.code=='1010')].balanceMinor").value(1_000_000))
                .andExpect(jsonPath("$.data[?(@.code=='1020')].balanceMinor").value(350_000))
                .andExpect(jsonPath("$.data[?(@.code=='1100')].balanceMinor").value(2_200_000))
                .andExpect(jsonPath("$.data[?(@.code=='1110')].balanceMinor").value(50_000))
                .andExpect(jsonPath("$.data[?(@.code=='1200')].balanceMinor").value(5_500_000))
                .andExpect(jsonPath("$.data[?(@.code=='2000')].balanceMinor").value(5_000_000))
                .andExpect(jsonPath("$.data[?(@.code=='3000')].balanceMinor").value(4_300_000));
        mvc.perform(chama.chair().get("/api/v1/ledger/me")).andExpect(jsonPath("$.data.savingsMinor").value(1_000_000));

        // Alice sets her password from the welcome email, then sees her savings and loan.
        String welcome = emails.awaitEmail(alice, s -> s.endsWith("set your password")).body();
        assertThat(welcome).contains("brought forward");
        mvc.perform(post("/api/v1/auth/password/reset").header("X-Chama-Code", chama.code())
                        .contentType("application/json")
                        .content("{\"token\":\"%s\",\"newPassword\":\"%s\"}".formatted(
                                RecordingEmailSender.tokenFrom(emails.awaitEmail(alice, s -> s.endsWith("set your password"))),
                                TestAccounts.PASSWORD)))
                .andExpect(status().isOk());
        Account aliceAccount = accounts.login(chama.code(), alice);
        mvc.perform(aliceAccount.get("/api/v1/members/me"))
                .andExpect(jsonPath("$.data.membership.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.membership.roles").value(hasItem("TREASURER")))
                .andExpect(jsonPath("$.data.membership.memberNumber").value(2));
        mvc.perform(aliceAccount.get("/api/v1/ledger/me"))
                .andExpect(jsonPath("$.data.savingsMinor").value(2_000_000))
                .andExpect(jsonPath("$.data.loansOutstandingMinor").value(2_200_000));
        String loans = mvc.perform(aliceAccount.get("/api/v1/loans/me"))
                .andExpect(jsonPath("$.data.loans[0].loan.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.loans[0].loan.outstandingMinor").value(2_200_000))
                .andReturn().getResponse().getContentAsString();

        // From now on everything is recorded in the system: a repayment on the imported loan.
        UUID loanId = UUID.fromString(JsonPath.read(loans, "$.data.loans[0].loan.id"));
        mvc.perform(chama.chair().post("/api/v1/loans/{id}/repayments",
                        "{\"amountMinor\":275000,\"method\":\"CASH\"}", loanId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.loan.outstandingMinor").value(2_200_000 - 275_000));

        mvc.perform(chama.chair().get("/api/v1/penalties?status=OUTSTANDING"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].amountMinor").value(50_000))
                .andExpect(jsonPath("$.data[0].description").value(containsString("brought forward")));
        mvc.perform(chama.chair().get("/api/v1/investments/portfolio"))
                .andExpect(jsonPath("$.data.activeCount").value(2))
                .andExpect(jsonPath("$.data.bookValueMinor").value(5_500_000))
                .andExpect(jsonPath("$.data.marketValueMinor").value(2_650_000 + 3_000_000));

        // Once only.
        upload(chama.chair(), URL, file)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IMPORT_ALREADY_DONE"));
        mvc.perform(chama.chair().get(URL))
                .andExpect(jsonPath("$.data.asOf").value(asOf.toString()))
                .andExpect(jsonPath("$.data.summary.newMembers").value(3));
    }

    @Test
    void theTemplateDownloadsAsAnExcelFile() throws Exception {
        TestChama chama = accounts.onboard("Atieno");

        mvc.perform(chama.chair().get(URL + "/template"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("opening-balances-template.xlsx")))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("spreadsheetml")));
    }

    @Test
    void balancesInOtherSavingsProductsComeInThroughTheirOwnColumns() throws Exception {
        TestChama chama = accounts.onboard("Nduta");
        mvc.perform(chama.chair().post("/api/v1/savings-products", """
                        {"code":"SHR","name":"Share capital","kind":"SHARE_CAPITAL","contributionMinor":0,"mandatory":true}"""))
                .andExpect(status().isCreated());
        mvc.perform(chama.chair().post("/api/v1/savings-products", """
                        {"code":"HOL","name":"Holiday savings","kind":"CUSTOM","contributionMinor":0,"mandatory":false}"""))
                .andExpect(status().isCreated());
        String alice = TestAccounts.uniqueEmail("alice");

        // Columns after Penalties, in product order: Holiday savings (HOL), then Share capital (SHR).
        byte[] file = Workbooks.from(template(chama.chair()))
                .row("Members", alice, "Alice", "Wanjiku", "0712345601", asOf.minusYears(2), null, 10000, null, 500, 2000)
                .setRow("Accounts", 2, "Cash on hand", 12500)
                .bytes();

        upload(chama.chair(), URL + "/preview", file)
                .andExpect(jsonPath("$.data.importable").value(true))
                .andExpect(jsonPath("$.data.summary.savingsMinor").value(1_250_000));
        upload(chama.chair(), URL, file).andExpect(status().isCreated());

        mvc.perform(chama.chair().get("/api/v1/ledger/accounts"))
                .andExpect(jsonPath("$.data[?(@.code=='2000')].balanceMinor").value(1_000_000))
                .andExpect(jsonPath("$.data[?(@.code=='2010')].balanceMinor").value(50_000))
                .andExpect(jsonPath("$.data[?(@.code=='3100')].balanceMinor").value(200_000));
        mvc.perform(chama.chair().get("/api/v1/member-accounts"))
                .andExpect(jsonPath("$.data[?(@.productCode=='HOL')].balanceMinor").value(50_000))
                // The chairperson's share account is empty; Alice's holds what she brought in.
                .andExpect(jsonPath("$.data[?(@.productCode=='SHR')].balanceMinor").value(containsInAnyOrder(0, 200_000)));
    }

    @Test
    void problemsAreListedAndNothingIsImported() throws Exception {
        TestChama chama = accounts.onboard("Kamau");
        byte[] file = Workbooks.from(template(chama.chair()))
                .row("Members", "not-an-email", "X", "Y", "0712345678", asOf, null, 100, null)
                .row("Members", TestAccounts.uniqueEmail("late"), "Late", "Joiner", "0712345678", asOf.plusDays(3), null, 100, null)
                .row("Loans", TestAccounts.uniqueEmail("ghost"), 1000, 0, 6, asOf, 0, null)
                .bytes();

        upload(chama.chair(), URL + "/preview", file)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.importable").value(false))
                .andExpect(jsonPath("$.data.errors").value(3))
                .andExpect(jsonPath("$.data.problems[0].sheet").value("Members"))
                .andExpect(jsonPath("$.data.problems[0].row").value(2))
                .andExpect(jsonPath("$.data.problems[0].column").value("Email"));
        upload(chama.chair(), URL, file)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("IMPORT_HAS_PROBLEMS"));
        mvc.perform(chama.chair().get(URL)).andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void onlyTheChairpersonImportsAndOnlyWorkbooksAreAccepted() throws Exception {
        TestChama chama = accounts.onboard("Otieno");
        Account member = accounts.member(chama, "Brian");

        mvc.perform(member.get(URL + "/template")).andExpect(status().isForbidden());
        mvc.perform(multipart(URL + "/preview").file(new MockMultipartFile("file", "members.csv", "text/csv",
                                "email,savings\na@x.test,100".getBytes()))
                        .param("asOf", asOf.toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + chama.chair().accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("IMPORT_INVALID_FILE"));
    }

    // ---- helpers -------------------------------------------------------------------------------

    private byte[] template(Account chair) throws Exception {
        return mvc.perform(chair.get(URL + "/template")).andReturn().getResponse().getContentAsByteArray();
    }

    private ResultActions upload(Account official, String url, byte[] file) throws Exception {
        return mvc.perform(multipart(url)
                .file(new MockMultipartFile("file", "mokica.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", file))
                .param("asOf", asOf.toString())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + official.accessToken()));
    }
}
