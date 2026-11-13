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
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A chama that ran for two years before using the system brings its history over: first when its
 * members joined and what they owed per month (KES 1,000 a month the first year, 2,000 the second),
 * then every contribution. The chairperson paid every month (36,000 in all); Alice paid 30,000 by
 * M-Pesa and is 6,000 behind for those years. Those years are never penalised automatically.
 */
@IntegrationTest
public class HistoryImportIntegrationTests {

    static final String URL = "/api/v1/imports/history";
    static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired
    MockMvc mvc;

    @Autowired
    RecordingEmailSender emails;

    TestAccounts accounts;
    LocalDate today;
    YearMonth thisMonth;
    LocalDate start;

    @BeforeEach
    void setUp() {
        accounts = new TestAccounts(mvc, emails);
        today = LocalDate.now(NAIROBI);
        thisMonth = YearMonth.from(today);
        start = thisMonth.minusMonths(24).atDay(1);
    }

    @Test
    void aChamaBringsTwoYearsOfContributionsOver() throws Exception {
        TestChama chama = accounts.onboard("Wanjiru");
        Account alice = accounts.member(chama, "Alice");
        Account chair = chama.chair();

        // Contributions cannot come before the setup.
        upload(chair, URL + "/contributions/preview", Workbooks.from(template(chair, "contributions"))
                .row("Contributions", "1", start.plusDays(2), 1000, "Cash").bytes())
                .andExpect(jsonPath("$.data.importable").value(false))
                .andExpect(jsonPath("$.data.problems[0].message").value(containsString("Import the setup first")));

        // ---- step 1: setup ----------------------------------------------------------------------
        byte[] setup = Workbooks.from(template(chair, "setup"))
                .setRow("Members", 2, null, null, null, start)
                .setRow("Members", 3, null, null, null, start)
                .setRow("Contribution schedule", 2, "DEP - Deposits", start, 1000)
                .row("Contribution schedule", "DEP", thisMonth.minusMonths(12).atDay(1), 2000)
                .bytes();
        upload(chair, URL + "/setup/preview", setup)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.importable").value(true))
                .andExpect(jsonPath("$.data.summary[?(@.label=='Join dates moved back')].value").value(hasItem(2)))
                .andExpect(jsonPath("$.data.summary[?(@.label=='Contribution periods to create')].value").value(hasItem(24)));
        upload(chair, URL + "/setup", setup)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.kind").value("SETUP"))
                .andExpect(jsonPath("$.data.summary[?(@.label=='Contribution periods created')].value").value(hasItem(24)));

        // The setup is done once; opening balances are no longer available.
        upload(chair, URL + "/setup/preview", setup)
                .andExpect(jsonPath("$.data.importable").value(false))
                .andExpect(jsonPath("$.data.problems[0].message").value(containsString("already imported")));
        mvc.perform(multipart("/api/v1/imports/opening-balances/preview")
                        .file(new MockMultipartFile("file", "x.xlsx", XLSX, setup))
                        .param("asOf", today.toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + chair.accessToken()))
                .andExpect(jsonPath("$.data.problems[0].message").value(containsString("history is being imported")));

        // ---- step 2: contributions --------------------------------------------------------------
        Workbooks contributions = Workbooks.from(template(chair, "contributions"));
        for (int i = 0; i < 24; i++) {
            YearMonth month = YearMonth.from(start).plusMonths(i);
            contributions.row("Contributions", "1", month.atDay(3), i < 12 ? 1000 : 2000, "Cash");
        }
        contributions.row("Contributions", "2 - Alice", start.plusMonths(20).withDayOfMonth(10), 30000, "M-Pesa",
                "QK7H2XY9ZB", null, "Paid in one go");
        byte[] file = contributions.bytes();

        // Mistakes are reported row by row, and nothing is saved.
        byte[] wrong = Workbooks.from(template(chair, "contributions"))
                .row("Contributions", "99", start, 1000, "Cash")
                .row("Contributions", "2", today.plusDays(3), 1000, "Cheque card")
                .row("Contributions", "2", start, -5, "Cash")
                .bytes();
        upload(chair, URL + "/contributions/preview", wrong)
                .andExpect(jsonPath("$.data.importable").value(false))
                .andExpect(jsonPath("$.data.errors").value(4))
                .andExpect(jsonPath("$.data.problems[?(@.row==2 && @.column=='Member')]").isNotEmpty())
                .andExpect(jsonPath("$.data.problems[?(@.row==3 && @.column=='Paid on')]").isNotEmpty())
                .andExpect(jsonPath("$.data.problems[?(@.row==3 && @.column=='Method')]").isNotEmpty())
                .andExpect(jsonPath("$.data.problems[?(@.row==4 && @.column=='Amount')]").isNotEmpty());
        upload(chair, URL + "/contributions", wrong).andExpect(status().isUnprocessableEntity());

        long currentDue = today.getDayOfMonth() >= 5 ? 500_000 : 0;
        upload(chair, URL + "/contributions/preview", file)
                .andExpect(jsonPath("$.data.importable").value(true))
                .andExpect(jsonPath("$.data.rows").value(25))
                .andExpect(jsonPath("$.data.summary[?(@.label=='Total')].value").value(hasItem(6_600_000)))
                .andExpect(jsonPath("$.data.tables[0].rows[1][5]").value(containsString("Arrears")));
        upload(chair, URL + "/contributions", file)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.summary[?(@.label=='Payments')].value").value(hasItem(25)));
        assertThat(emails.sentTo(alice.email()).stream().filter(e -> e.subject().contains("ontribution")))
                .as("no receipts for history").isEmpty();

        // Where members stand: dues of every month since they joined, against what they paid.
        String standings = mvc.perform(chair.get("/api/v1/contributions/standings"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Integer> chairArrears = JsonPath.read(standings, "$.data[?(@.memberNumber==1)].arrearsMinor");
        List<Integer> aliceArrears = JsonPath.read(standings, "$.data[?(@.memberNumber==2)].arrearsMinor");
        assertThat(chairArrears).containsExactly((int) currentDue);
        assertThat(aliceArrears).containsExactly((int) (600_000 + currentDue));

        // The books: each payment on its own day, to the account it came into.
        mvc.perform(chair.get("/api/v1/ledger/accounts"))
                .andExpect(jsonPath("$.data[?(@.code=='1000')].balanceMinor").value(hasItem(3_600_000)))
                .andExpect(jsonPath("$.data[?(@.code=='1020')].balanceMinor").value(hasItem(3_000_000)))
                .andExpect(jsonPath("$.data[?(@.code=='2000')].balanceMinor").value(hasItem(6_600_000)));
        mvc.perform(alice.get("/api/v1/contributions/me"))
                .andExpect(jsonPath("$.data.recentPayments[0].reference").value("QK7H2XY9ZB"))
                .andExpect(jsonPath("$.data.recentPayments[0].paidOn").value(start.plusMonths(20).withDayOfMonth(10).toString()));

        // The years before the system are never penalised automatically.
        mvc.perform(chair.post("/api/v1/penalties/assess")).andExpect(status().isOk());
        String penalties = mvc.perform(chair.get("/api/v1/penalties"))
                .andReturn().getResponse().getContentAsString();
        List<String> descriptions = JsonPath.read(penalties, "$.data[*].description");
        assertThat(descriptions).allSatisfy(d -> assertThat(d).contains(thisMonth.toString()));

        // ---- undo, latest first -----------------------------------------------------------------
        String batches = mvc.perform(chair.get(URL + "/batches"))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].kind").value("CONTRIBUTIONS"))
                .andExpect(jsonPath("$.data[0].undoable").value(true))
                .andExpect(jsonPath("$.data[1].undoable").value(false))
                .andReturn().getResponse().getContentAsString();
        String contributionsBatch = JsonPath.read(batches, "$.data[0].id");
        String setupBatch = JsonPath.read(batches, "$.data[1].id");

        mvc.perform(chair.post(URL + "/batches/{id}/undo", java.util.UUID.fromString(setupBatch)))
                .andExpect(status().isConflict());
        mvc.perform(chair.post(URL + "/batches/{id}/undo", java.util.UUID.fromString(contributionsBatch)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.undoneAt").isNotEmpty());
        mvc.perform(chair.get("/api/v1/ledger/accounts"))
                .andExpect(jsonPath("$.data[?(@.code=='1000')].balanceMinor").value(hasItem(0)))
                .andExpect(jsonPath("$.data[?(@.code=='2000')].balanceMinor").value(hasItem(0)));

        mvc.perform(chair.post(URL + "/batches/{id}/undo", java.util.UUID.fromString(setupBatch)))
                .andExpect(status().isOk());
        String after = mvc.perform(chair.get("/api/v1/contributions/standings"))
                .andReturn().getResponse().getContentAsString();
        List<Integer> aliceDue = JsonPath.read(after, "$.data[?(@.memberNumber==2)].dueToDateMinor");
        // Alice was added today: she owes this month only if it falls due today.
        assertThat(aliceDue).as("the years before are gone").containsExactly(today.getDayOfMonth() == 5 ? 500_000 : 0);
        mvc.perform(chair.get("/api/v1/contributions/periods"))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    /**
     * Loans at 10% flat (the default product's rate, used when Interest is blank). Alice borrowed
     * 10,000 two years ago and repaid 11,000; six months ago she borrowed 12,000 over 12 months
     * (1,100 a month) and has paid three instalments, so she owes 9,900 and is behind.
     */
    @Test
    void loansComeOverWithTheirSchedules() throws Exception {
        TestChama chama = accounts.onboard("Achieng");
        Account alice = accounts.member(chama, "Alice");
        Account chair = chama.chair();
        importSetup(chair);

        LocalDate secondLoan = thisMonth.minusMonths(6).atDay(1);
        byte[] wrong = Workbooks.from(template(chair, "loans"))
                .row("Loans", "L1", "2", start.plusMonths(1), 10000, null, 10)
                .row("Loans", "L2", "2", secondLoan, 12000, null, 12)
                .row("Loans", "L3", "1", start.plusMonths(1), 1000, null, 1)
                .row("Repayments", "L3", start.plusMonths(3), 2000, "Cash")
                .row("Repayments", "L9", start.plusMonths(3), 100, "Cash")
                .bytes();
        upload(chair, URL + "/loans/preview", wrong)
                .andExpect(jsonPath("$.data.importable").value(false))
                .andExpect(jsonPath("$.data.problems[?(@.sheet=='Repayments' && @.row==2)].message")
                        .value(hasItem(containsString("add up to"))))
                .andExpect(jsonPath("$.data.problems[?(@.sheet=='Repayments' && @.row==3)].message")
                        .value(hasItem(containsString("No loan L9"))))
                .andExpect(jsonPath("$.data.problems[?(@.sheet=='Loans' && @.row==3)].message")
                        .value(hasItem(containsString("two"))));

        Workbooks loans = Workbooks.from(template(chair, "loans"))
                .row("Loans", "L1", "2 - Alice", start.plusMonths(1), 10000, null, 10, null, "School fees")
                .row("Loans", "L2", "2", secondLoan, 12000, null, 12, null, "Stock", "M-Pesa")
                .row("Repayments", "L1", start.plusMonths(4), 5500, "Cash")
                .row("Repayments", "L1", start.plusMonths(8), 5500, "Cash");
        for (int n = 1; n <= 3; n++) {
            loans.row("Repayments", "L2", secondLoan.plusMonths(n), 1100, "M-Pesa", "QL2REPAY" + n);
        }
        byte[] file = loans.bytes();
        long overdue = today.getDayOfMonth() > 1 ? 330_000 : 220_000;
        upload(chair, URL + "/loans/preview", file)
                .andExpect(jsonPath("$.data.importable").value(true))
                .andExpect(jsonPath("$.data.summary[?(@.label=='Interest')].value").value(hasItem(220_000)))
                .andExpect(jsonPath("$.data.summary[?(@.label=='Outstanding')].value").value(hasItem(990_000)))
                .andExpect(jsonPath("$.data.summary[?(@.label=='Overdue')].value").value(hasItem((int) overdue)))
                .andExpect(jsonPath("$.data.tables[0].rows[0][10]").value("Repaid"))
                .andExpect(jsonPath("$.data.tables[0].rows[1][10]").value("Being repaid"))
                .andExpect(jsonPath("$.data.problems[?(@.column=='Reference')].message")
                        .value(hasItem(containsString("no reference"))));
        upload(chair, URL + "/loans", file).andExpect(status().isCreated());

        mvc.perform(alice.get("/api/v1/loans/me"))
                .andExpect(jsonPath("$.data.outstandingMinor").value(990_000))
                .andExpect(jsonPath("$.data.arrearsMinor").value((int) overdue))
                .andExpect(jsonPath("$.data.loans.length()").value(2))
                .andExpect(jsonPath("$.data.loans[?(@.loan.status=='REPAID')]").isNotEmpty())
                .andExpect(jsonPath("$.data.loans[?(@.loan.status=='ACTIVE')].schedule.length()").value(hasItem(12)));
        // Loans receivable: what Alice owes. Cash paid out 10,000 and took in 11,000; M-Pesa paid out
        // 12,000 and took in 3,300. Interest is income when the loan is paid out, as for any loan.
        mvc.perform(chair.get("/api/v1/ledger/accounts"))
                .andExpect(jsonPath("$.data[?(@.code=='1100')].balanceMinor").value(hasItem(990_000)))
                .andExpect(jsonPath("$.data[?(@.code=='1000')].balanceMinor").value(hasItem(100_000)))
                .andExpect(jsonPath("$.data[?(@.code=='1020')].balanceMinor").value(hasItem(-870_000)));

        // The schedules download: one row per loan being repaid, one per instalment.
        byte[] xlsx = mvc.perform(chair.get("/api/v1/loans/schedules"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (var book = org.apache.poi.ss.usermodel.WorkbookFactory.create(new java.io.ByteArrayInputStream(xlsx))) {
            var summary = book.getSheet("Summary");
            assertThat(summary.getRow(3).getCell(1).getStringCellValue()).startsWith("Alice");
            assertThat(summary.getRow(3).getCell(10).getNumericCellValue()).isEqualTo(9900.0);
            assertThat(book.getSheet("Schedules").getLastRowNum()).isEqualTo(12);
            assertThat(book.getSheet("Schedules").getRow(1).getCell(8).getStringCellValue()).isEqualTo("Paid");
        }
        mvc.perform(alice.get("/api/v1/loans/schedules")).andExpect(status().isForbidden());

        // Undo, import again, then a repayment recorded by hand keeps the import in place.
        String batches = mvc.perform(chair.get(URL + "/batches")).andReturn().getResponse().getContentAsString();
        java.util.UUID loansBatch = java.util.UUID.fromString(JsonPath.read(batches, "$.data[0].id"));
        mvc.perform(chair.post(URL + "/batches/{id}/undo", loansBatch)).andExpect(status().isOk());
        mvc.perform(alice.get("/api/v1/loans/me")).andExpect(jsonPath("$.data.loans.length()").value(0));
        mvc.perform(chair.get("/api/v1/ledger/accounts"))
                .andExpect(jsonPath("$.data[?(@.code=='1100')].balanceMinor").value(hasItem(0)));

        upload(chair, URL + "/loans", file).andExpect(status().isCreated());
        String mine = mvc.perform(alice.get("/api/v1/loans/me")).andReturn().getResponse().getContentAsString();
        List<String> active = JsonPath.read(mine, "$.data.loans[?(@.loan.status=='ACTIVE')].loan.id");
        mvc.perform(chair.post("/api/v1/loans/{id}/repayments", "{\"amountMinor\":110000,\"method\":\"CASH\"}",
                        java.util.UUID.fromString(active.getFirst())))
                .andExpect(status().isCreated());
        mvc.perform(chair.get(URL + "/batches"))
                .andExpect(jsonPath("$.data[0].undoable").value(false))
                .andExpect(jsonPath("$.data[0].undoBlockedReason").value(containsString("recorded on these loans")));
    }

    /**
     * Penalties, investments and expenses, then the balances checked against the statements.
     * Cash: +500 penalty paid, -5,000 top-up, -100 fund fee, -50,000 land = -54,600 in the books
     * (the chama really held 1,000: money from before its records). Bank: -20,000 into the fund,
     * +3,000 withdrawn, +60,000 from selling the land, -150 charges = 42,850, as the statement says.
     */
    @Test
    void penaltiesInvestmentsAndExpensesComeOverAndTheBalancesAreChecked() throws Exception {
        TestChama chama = accounts.onboard("Njeri");
        Account alice = accounts.member(chama, "Alice");
        Account chair = chama.chair();
        importSetup(chair);

        // ---- penalties ----------------------------------------------------------------------
        byte[] penalties = Workbooks.from(template(chair, "penalties"))
                .row("Penalties", "P1", "2", start.plusMonths(2), 500, "Fine", "Missed the March meeting")
                .row("Penalties", "P2", "2", start.plusMonths(4), 200, "Late contribution", null, start.plusMonths(5),
                        "First time")
                .row("Penalties", "P3", "1", start.plusMonths(6), 300)
                .row("Penalty payments", "P1", start.plusMonths(3), 500, "Cash")
                .bytes();
        upload(chair, URL + "/penalties/preview", penalties)
                .andExpect(jsonPath("$.data.importable").value(true))
                .andExpect(jsonPath("$.data.summary[?(@.label=='Still owed')].value").value(hasItem(30_000)))
                .andExpect(jsonPath("$.data.summary[?(@.label=='Waived')].value").value(hasItem(20_000)));
        upload(chair, URL + "/penalties", penalties).andExpect(status().isCreated());
        // This month (in the system) is penalised as usual once its grace days are over: 2% of 5,000 each.
        boolean thisMonthLate = today.getDayOfMonth() > 10;
        int live = thisMonthLate ? 10_000 : 0;
        mvc.perform(chair.get("/api/v1/penalties"))
                .andExpect(jsonPath("$.data.length()").value(thisMonthLate ? 5 : 3))
                .andExpect(jsonPath("$.data[?(@.kind=='LATE_CONTRIBUTION')].description")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.not(containsString(
                                YearMonth.from(start).plusMonths(4).toString())))))
                .andExpect(jsonPath("$.data[?(@.description=='Missed the March meeting')].status").value(hasItem("PAID")))
                .andExpect(jsonPath("$.data[?(@.kind=='LATE_CONTRIBUTION')].status").value(hasItem("WAIVED")));
        mvc.perform(alice.get("/api/v1/penalties/me")).andExpect(jsonPath("$.data.outstandingMinor").value(live));
        mvc.perform(chair.get("/api/v1/ledger/accounts"))
                .andExpect(jsonPath("$.data[?(@.code=='1110')].balanceMinor").value(hasItem(30_000 + 2 * live)));

        // ---- investments --------------------------------------------------------------------
        byte[] tooMuch = Workbooks.from(template(chair, "investments"))
                .row("Investments", "I1", "CIC fund", "Money market fund", "CIC", start.plusMonths(1), 20000, "Cash")
                .row("Investment activity", "I1", start.plusMonths(2), "Withdrawal", 25000, "Cash")
                .bytes();
        upload(chair, URL + "/investments/preview", tooMuch)
                .andExpect(jsonPath("$.data.importable").value(false))
                .andExpect(jsonPath("$.data.problems[0].message").value(containsString("less than the investment")));

        byte[] investments = Workbooks.from(template(chair, "investments"))
                .row("Investments", "I1", "CIC money market fund", "Money market fund", "CIC", start.plusMonths(1), 20000,
                        "Bank", "BNK001", 11)
                .row("Investments", "I2", "Plot in Kitengela", "LAND", null, start.plusMonths(2), 50000)
                .row("Investment activity", "I1", start.plusMonths(6), "Income", 1000, "Reinvested")
                .row("Investment activity", "I1", start.plusMonths(8), "Deposit", 5000, "Cash")
                .row("Investment activity", "I1", start.plusMonths(12), "Valuation", 27500)
                .row("Investment activity", "I1", start.plusMonths(13), "Withdrawal", 3000, "Bank", "BNK002")
                .row("Investment activity", "I2", start.plusMonths(20), "Closure", 60000, "Bank", "BNK003", "Sold")
                .bytes();
        upload(chair, URL + "/investments/preview", investments)
                .andExpect(jsonPath("$.data.importable").value(true))
                .andExpect(jsonPath("$.data.tables[0].rows[1][9]").value(containsString("gain")));
        upload(chair, URL + "/investments", investments).andExpect(status().isCreated());
        mvc.perform(chair.get("/api/v1/investments/portfolio"))
                .andExpect(jsonPath("$.data.activeCount").value(1))
                .andExpect(jsonPath("$.data.bookValueMinor").value(2_300_000))
                .andExpect(jsonPath("$.data.realisedGainMinor").value(1_000_000));

        // ---- expenses -----------------------------------------------------------------------
        byte[] expenses = Workbooks.from(template(chair, "expenses"))
                .row("Expenses", start.plusMonths(3), "KCB", "Bank charges", 150, null, "Bank")
                .row("Expenses", start.plusMonths(9), "CIC", "Fund management fee", 100, null, "Cash", null,
                        "CIC money market fund")
                .bytes();
        upload(chair, URL + "/expenses/preview", expenses)
                .andExpect(jsonPath("$.data.importable").value(true))
                .andExpect(jsonPath("$.data.summary[?(@.label=='Total')].value").value(hasItem(25_000)));
        upload(chair, URL + "/expenses", expenses).andExpect(status().isCreated());

        // ---- the balances -------------------------------------------------------------------
        byte[] balances = Workbooks.from(template(chair, "reconciliation"))
                .setRow("Balances", 2, null, null, 1000)
                .setRow("Balances", 3, null, null, 42850)
                .setRow("Balances", 4, null, null, 500)
                .bytes();
        upload(chair, URL + "/reconciliation/preview", balances)
                .andExpect(jsonPath("$.data.importable").value(true))
                .andExpect(jsonPath("$.data.tables[0].rows[?(@[0]=='Cash on hand')][2]").value(hasItem("KES -54,600.00")))
                .andExpect(jsonPath("$.data.tables[0].rows[?(@[0]=='Bank account')][4]").value(hasItem("Matches")))
                .andExpect(jsonPath("$.data.summary[?(@.label=='Net adjustment')].value").value(hasItem(5_610_000)));
        upload(chair, URL + "/reconciliation", balances).andExpect(status().isCreated());
        mvc.perform(chair.get("/api/v1/ledger/accounts"))
                .andExpect(jsonPath("$.data[?(@.code=='1000')].balanceMinor").value(hasItem(100_000)))
                .andExpect(jsonPath("$.data[?(@.code=='1010')].balanceMinor").value(hasItem(4_285_000)))
                .andExpect(jsonPath("$.data[?(@.code=='1020')].balanceMinor").value(hasItem(50_000)));

        // Undone latest first: the adjustment, then the expenses.
        String batches = mvc.perform(chair.get(URL + "/batches"))
                .andExpect(jsonPath("$.data.length()").value(5))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(chair.post(URL + "/batches/{id}/undo", java.util.UUID.fromString(JsonPath.read(batches, "$.data[0].id"))))
                .andExpect(status().isOk());
        mvc.perform(chair.post(URL + "/batches/{id}/undo", java.util.UUID.fromString(JsonPath.read(batches, "$.data[1].id"))))
                .andExpect(status().isOk());
        mvc.perform(chair.get("/api/v1/ledger/accounts"))
                .andExpect(jsonPath("$.data[?(@.code=='1000')].balanceMinor").value(hasItem(-5_450_000)))
                .andExpect(jsonPath("$.data[?(@.code=='1010')].balanceMinor").value(hasItem(4_300_000)));
    }

    /** Both members joined two years ago; KES 1,000 a month. */
    private void importSetup(Account chair) throws Exception {
        byte[] setup = Workbooks.from(template(chair, "setup"))
                .setRow("Members", 2, null, null, null, start)
                .setRow("Members", 3, null, null, null, start)
                .setRow("Contribution schedule", 2, "DEP", start, 1000)
                .bytes();
        upload(chair, URL + "/setup", setup).andExpect(status().isCreated());
    }

    private byte[] template(Account account, String kind) throws Exception {
        return mvc.perform(account.get(URL + "/{kind}/template", kind))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
    }

    private ResultActions upload(Account account, String url, byte[] file) throws Exception {
        return mvc.perform(multipart(url)
                .file(new MockMultipartFile("file", "history.xlsx", XLSX, file))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + account.accessToken()));
    }
}
