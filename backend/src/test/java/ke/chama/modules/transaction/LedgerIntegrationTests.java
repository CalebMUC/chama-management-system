package ke.chama.modules.transaction;

import com.jayway.jsonpath.JsonPath;
import ke.chama.IntegrationTest;
import ke.chama.RecordingEmailSender;
import ke.chama.TestAccounts;
import ke.chama.TestAccounts.Account;
import ke.chama.TestAccounts.TestChama;
import ke.chama.TestcontainersConfiguration.OwnerJdbc;
import ke.chama.modules.transaction.api.Ledger;
import ke.chama.modules.transaction.api.LedgerAccountKey;
import ke.chama.modules.transaction.api.PostingRequest;
import ke.chama.modules.transaction.api.PostingRequest.PostingLine;
import ke.chama.shared.domain.Money;
import ke.chama.shared.tenancy.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The double-entry ledger over HTTP and through its module API, against a real database. */
@IntegrationTest
class LedgerIntegrationTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    RecordingEmailSender emails;

    @Autowired
    OwnerJdbc owner;

    @Autowired
    Ledger ledger;

    @Autowired
    PlatformTransactionManager transactionManager;

    TestAccounts accounts;

    @BeforeEach
    void setUp() {
        accounts = new TestAccounts(mvc, emails);
    }

    @Nested
    class ChartOfAccounts {

        @Test
        void everyNewChamaGetsTheStandardChart() throws Exception {
            TestChama chama = accounts.onboard("Achieng");

            mvc.perform(chama.chair().get("/api/v1/ledger/accounts"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(13))
                    .andExpect(jsonPath("$.data[*].code").value(hasItems("1000", "1010", "1020", "1100", "2000", "4000", "5000")))
                    .andExpect(jsonPath("$.data[?(@.code=='2000')].memberLedger").value(true))
                    .andExpect(jsonPath("$.data[?(@.code=='2000')].type").value("LIABILITY"))
                    .andExpect(jsonPath("$.data[*].balanceMinor").value(org.hamcrest.Matchers.everyItem(
                            org.hamcrest.Matchers.is(0))));
        }

        @Test
        void chamasAddTheirOwnAccounts() throws Exception {
            TestChama chama = accounts.onboard("Kamau");

            mvc.perform(chama.chair().post("/api/v1/ledger/accounts",
                            "{\"code\":\"1030\",\"name\":\"Equity Bank savings\",\"type\":\"ASSET\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.code").value("1030"))
                    .andExpect(jsonPath("$.data.currency").value("KES"));
            mvc.perform(chama.chair().post("/api/v1/ledger/accounts", "{\"code\":\"1030\",\"name\":\"Again\",\"type\":\"ASSET\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("LEDGER_ACCOUNT_CODE_TAKEN"));
        }

        @Test
        void systemAccountsCannotBeDeactivatedButInactiveAccountsRefusePostings() throws Exception {
            TestChama chama = accounts.onboard("Njoroge");
            String body = mvc.perform(chama.chair().post("/api/v1/ledger/accounts",
                    "{\"code\":\"1040\",\"name\":\"Old SACCO account\",\"type\":\"ASSET\"}")).andReturn().getResponse().getContentAsString();
            UUID custom = UUID.fromString(JsonPath.read(body, "$.data.id"));

            mvc.perform(chama.chair().put("/api/v1/ledger/accounts/{id}/status", "{\"active\":false}", accountId(chama, "1000")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("LEDGER_INVALID_ACCOUNT"));
            mvc.perform(chama.chair().put("/api/v1/ledger/accounts/{id}/status", "{\"active\":false}", custom))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.active").value(false));

            mvc.perform(chama.chair().post("/api/v1/ledger/entries", entryJson("Into closed account",
                            line("1040", 1000, 0, null), line("1000", 0, 1000, null))))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("LEDGER_ACCOUNT_INACTIVE"));
        }
    }

    @Nested
    class Posting {

        @Test
        void openingBalancesShowUpInBalancesStatementsAndTheTrialBalance() throws Exception {
            TestChama chama = accounts.onboard("Wanjiru");
            Account chair = chama.chair();
            Account member = accounts.member(chama, "Kevin");

            mvc.perform(chair.post("/api/v1/ledger/entries", entryJson("Opening balances",
                            line("1010", 150_000_00, 0, null),
                            line("2000", 0, 100_000_00, chair.userId()),
                            line("2000", 0, 50_000_00, member.userId()))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.message").value("Entry #1 posted"))
                    .andExpect(jsonPath("$.data.number").value(1))
                    .andExpect(jsonPath("$.data.totalMinor").value(150_000_00))
                    .andExpect(jsonPath("$.data.lines.length()").value(3));

            mvc.perform(chair.get("/api/v1/ledger/accounts"))
                    .andExpect(jsonPath("$.data[?(@.code=='1010')].balanceMinor").value(150_000_00))
                    .andExpect(jsonPath("$.data[?(@.code=='2000')].balanceMinor").value(150_000_00));

            mvc.perform(chair.get("/api/v1/ledger/trial-balance"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.balanced").value(true))
                    .andExpect(jsonPath("$.data.totalDebitsMinor").value(150_000_00))
                    .andExpect(jsonPath("$.data.totalCreditsMinor").value(150_000_00));

            mvc.perform(chair.get("/api/v1/ledger/accounts/{id}/statement?memberUserId={m}", accountId(chama, "2000"), member.userId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.lines.length()").value(1))
                    .andExpect(jsonPath("$.data.closingBalanceMinor").value(50_000_00));

            // A member sees only their own position.
            mvc.perform(member.get("/api/v1/ledger/me"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.savingsMinor").value(50_000_00))
                    .andExpect(jsonPath("$.data.loansOutstandingMinor").value(0))
                    .andExpect(jsonPath("$.data.currency").value("KES"));
        }

        @Test
        void entriesThatDoNotBalanceAreRefused() throws Exception {
            TestChama chama = accounts.onboard("Otieno");

            mvc.perform(chama.chair().post("/api/v1/ledger/entries", entryJson("Wrong",
                            line("1000", 1000, 0, null), line("4900", 0, 999, null))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("LEDGER_UNBALANCED_ENTRY"));
        }

        @Test
        void memberAccountsNeedTheMemberAndOthersRefuseOne() throws Exception {
            TestChama chama = accounts.onboard("Chebet");
            Account chair = chama.chair();

            mvc.perform(chair.post("/api/v1/ledger/entries", entryJson("No member",
                            line("1000", 1000, 0, null), line("2000", 0, 1000, null))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("LEDGER_MEMBER_MISMATCH"));
            mvc.perform(chair.post("/api/v1/ledger/entries", entryJson("Member on cash",
                            line("1000", 1000, 0, chair.userId()), line("4900", 0, 1000, null))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("LEDGER_MEMBER_MISMATCH"));
            mvc.perform(chair.post("/api/v1/ledger/entries", entryJson("Stranger",
                            line("1000", 1000, 0, null), line("2000", 0, 1000, UUID.randomUUID()))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("LEDGER_MEMBER_MISMATCH"));
        }

        @Test
        void unknownAccountsAndFutureDatesAreRefused() throws Exception {
            TestChama chama = accounts.onboard("Kiprop");

            mvc.perform(chama.chair().post("/api/v1/ledger/entries", entryJson("Typo",
                            line("9999", 1000, 0, null), line("4900", 0, 1000, null))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("LEDGER_ACCOUNT_NOT_FOUND"));

            LocalDate tomorrow = LocalDate.now(ZoneId.of("Africa/Nairobi")).plusDays(1);
            mvc.perform(chama.chair().post("/api/v1/ledger/entries", """
                            {"entryDate":"%s","description":"Tomorrow","lines":[%s,%s]}"""
                            .formatted(tomorrow, line("1000", 1000, 0, null), line("4900", 0, 1000, null))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("LEDGER_INVALID_ENTRY"));
        }

        @Test
        void entriesAreNumberedInSequencePerChama() throws Exception {
            TestChama chama = accounts.onboard("Muthoni");
            for (int i = 0; i < 3; i++) {
                postCashIncome(chama, 1000);
            }

            String journal = mvc.perform(chama.chair().get("/api/v1/ledger/entries"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.meta.totalElements").value(3))
                    .andReturn().getResponse().getContentAsString();
            List<Integer> numbers = JsonPath.read(journal, "$.data[*].number");
            assertThat(numbers).containsExactly(3, 2, 1);
        }
    }

    @Nested
    class Reversal {

        @Test
        void aReversalCancelsTheEntryOnceAndOnlyOnce() throws Exception {
            TestChama chama = accounts.onboard("Atieno");
            UUID entryId = postCashIncome(chama, 25_000);

            mvc.perform(chama.chair().post("/api/v1/ledger/entries/{id}/reverse", "{\"reason\":\"Recorded twice\"}", entryId))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.number").value(2))
                    .andExpect(jsonPath("$.data.reversesEntryId").value(entryId.toString()))
                    .andExpect(jsonPath("$.data.sourceType").value("REVERSAL"));

            mvc.perform(chama.chair().get("/api/v1/ledger/entries/{id}", entryId))
                    .andExpect(jsonPath("$.data.reversedByEntryId").isString());
            mvc.perform(chama.chair().get("/api/v1/ledger/accounts"))
                    .andExpect(jsonPath("$.data[?(@.code=='1000')].balanceMinor").value(0))
                    .andExpect(jsonPath("$.data[?(@.code=='4900')].balanceMinor").value(0));

            mvc.perform(chama.chair().post("/api/v1/ledger/entries/{id}/reverse", "{\"reason\":\"Again\"}", entryId))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("LEDGER_ALREADY_REVERSED"));
        }

        @Test
        void aReversalCannotItselfBeReversed() throws Exception {
            TestChama chama = accounts.onboard("Rotich");
            UUID entryId = postCashIncome(chama, 25_000);
            String body = mvc.perform(chama.chair().post("/api/v1/ledger/entries/{id}/reverse", "{\"reason\":\"Mistake\"}", entryId))
                    .andReturn().getResponse().getContentAsString();
            UUID reversalId = UUID.fromString(JsonPath.read(body, "$.data.id"));

            mvc.perform(chama.chair().post("/api/v1/ledger/entries/{id}/reverse", "{\"reason\":\"Undo\"}", reversalId))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("LEDGER_CANNOT_REVERSE_REVERSAL"));
        }

        @Test
        void manualEntriesAndReversalsAreAudited() throws Exception {
            TestChama chama = accounts.onboard("Langat");
            UUID entryId = postCashIncome(chama, 5_000);
            mvc.perform(chama.chair().post("/api/v1/ledger/entries/{id}/reverse", "{\"reason\":\"Wrong amount\"}", entryId));

            String log = mvc.perform(chama.chair().get("/api/v1/audit-log")).andReturn().getResponse().getContentAsString();
            List<String> actions = JsonPath.read(log, "$.data[*].action");
            assertThat(actions).containsSubsequence("LEDGER_ENTRY_REVERSED", "LEDGER_ENTRY_POSTED");
            List<String> reasons = JsonPath.read(log, "$.data[?(@.action=='LEDGER_ENTRY_REVERSED')].details.reason");
            assertThat(reasons).containsExactly("Wrong amount");
        }
    }

    @Nested
    class Permissions {

        @Test
        void plainMembersSeeOnlyTheirOwnBalances() throws Exception {
            TestChama chama = accounts.onboard("Kerubo");
            Account member = accounts.member(chama, "Peter");

            mvc.perform(member.get("/api/v1/ledger/accounts")).andExpect(status().isForbidden());
            mvc.perform(member.get("/api/v1/ledger/trial-balance")).andExpect(status().isForbidden());
            mvc.perform(member.post("/api/v1/ledger/entries", entryJson("Sneaky",
                    line("1000", 1000, 0, null), line("4900", 0, 1000, null)))).andExpect(status().isForbidden());
            mvc.perform(member.get("/api/v1/ledger/me")).andExpect(status().isOk());
        }

        @Test
        void treasurersPostAndAuditorsOnlyRead() throws Exception {
            TestChama chama = accounts.onboard("Mwangi");
            Account treasurer = accounts.member(chama, "Tom");
            Account auditor = accounts.member(chama, "Grace");
            mvc.perform(chama.chair().put("/api/v1/members/{m}/roles", "{\"roles\":[\"TREASURER\"]}", accounts.membershipId(treasurer)));
            mvc.perform(chama.chair().put("/api/v1/members/{m}/roles", "{\"roles\":[\"AUDITOR\"]}", accounts.membershipId(auditor)));
            String entry = entryJson("Bank charges", line("5100", 3_500, 0, null), line("1010", 0, 3_500, null));

            mvc.perform(treasurer.post("/api/v1/ledger/entries", entry)).andExpect(status().isCreated());
            mvc.perform(auditor.get("/api/v1/ledger/trial-balance")).andExpect(status().isOk());
            mvc.perform(auditor.post("/api/v1/ledger/entries", entry)).andExpect(status().isForbidden());
        }

        @Test
        void anotherChamasEntriesAreInvisible() throws Exception {
            TestChama mokica = accounts.onboard("Mokica");
            TestChama jamii = accounts.onboard("Jamii");
            UUID mokicaEntry = postCashIncome(mokica, 1_000);

            mvc.perform(jamii.chair().get("/api/v1/ledger/entries/{id}", mokicaEntry))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("LEDGER_ENTRY_NOT_FOUND"));
            mvc.perform(jamii.chair().post("/api/v1/ledger/entries/{id}/reverse", "{\"reason\":\"x\"}", mokicaEntry))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    class ModuleApi {

        @Test
        void postingIsIdempotentPerSource() throws Exception {
            TestChama chama = accounts.onboard("Wekesa");
            Account member = accounts.member(chama, "Faith");
            String source = UUID.randomUUID().toString();
            PostingRequest contribution = new PostingRequest(chama.id(), null, "Contribution for November", "QK7H2XY9ZB",
                    "CONTRIBUTION", source, chama.chair().userId(), List.of(
                    PostingLine.debit(LedgerAccountKey.MOBILE_MONEY, Money.kes(500_000)),
                    PostingLine.credit(LedgerAccountKey.MEMBER_SAVINGS, Money.kes(500_000)).forMember(member.userId())));

            UUID first = inChama(chama, () -> ledger.post(contribution));
            UUID second = inChama(chama, () -> ledger.post(contribution));

            assertThat(second).isEqualTo(first);
            assertThat(inChama(chama, () -> ledger.memberBalance(chama.id(), member.userId(), LedgerAccountKey.MEMBER_SAVINGS)))
                    .isEqualTo(Money.kes(500_000));
            assertThat(inChama(chama, () -> ledger.balance(chama.id(), LedgerAccountKey.MOBILE_MONEY)))
                    .isEqualTo(Money.kes(500_000));
        }
    }

    @Nested
    class DatabaseGuarantees {

        @Test
        void entriesCannotBeEditedOrDeletedEvenByTheOwner() throws Exception {
            TestChama chama = accounts.onboard("Omondi");
            UUID entryId = postCashIncome(chama, 1_000);

            assertThatThrownBy(() -> owner.jdbc().update(
                    "update ledger.journal_lines set debit_minor = 999999 where entry_id = ?", entryId))
                    .hasMessageContaining("append-only");
            assertThatThrownBy(() -> owner.jdbc().update("delete from ledger.journal_entries where id = ?", entryId))
                    .hasMessageContaining("append-only");
        }

        @Test
        void theDatabaseRefusesAnUnbalancedEntry() throws Exception {
            TestChama chama = accounts.onboard("Kariuki");
            UUID cash = accountId(chama, "1000");

            assertThatThrownBy(() -> new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                    owner.jdbc().getDataSource())).executeWithoutResult(status -> {
                UUID entry = UUID.randomUUID();
                owner.jdbc().update("""
                        insert into ledger.journal_entries (id, tenant_id, entry_number, entry_date, description,
                                                            source_type, recorded_at)
                        values (?, ?, 9999, current_date, 'Forged', 'MANUAL', now())""", entry, chama.id());
                owner.jdbc().update("""
                        insert into ledger.journal_lines (id, tenant_id, entry_id, line_number, account_id, debit_minor)
                        values (?, ?, ?, 1, ?, 1000000)""", UUID.randomUUID(), chama.id(), entry, cash);
            })).rootCause().hasMessageContaining("not balanced");
        }
    }

    // ---- helpers -----------------------------------------------------------------------------

    private UUID postCashIncome(TestChama chama, long amount) throws Exception {
        String body = mvc.perform(chama.chair().post("/api/v1/ledger/entries", entryJson("Cash received",
                        line("1000", amount, 0, null), line("4900", 0, amount, null))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.data.id"));
    }

    private UUID accountId(TestChama chama, String code) throws Exception {
        String body = mvc.perform(chama.chair().get("/api/v1/ledger/accounts")).andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(body, "$.data[?(@.code=='" + code + "')].id");
        return UUID.fromString(ids.getFirst());
    }

    private <T> T inChama(TestChama chama, java.util.function.Supplier<T> work) {
        return TenantContext.callAs(chama.id(), chama.code(),
                () -> new TransactionTemplate(transactionManager).execute(status -> work.get()));
    }

    private static String entryJson(String description, String... lines) {
        return "{\"description\":\"%s\",\"lines\":[%s]}".formatted(description, String.join(",", lines));
    }

    private static String line(String code, long debit, long credit, UUID member) {
        return "{\"accountCode\":\"%s\",\"debitMinor\":%d,\"creditMinor\":%d%s}".formatted(code, debit, credit,
                member == null ? "" : ",\"memberUserId\":\"" + member + "\"");
    }
}
