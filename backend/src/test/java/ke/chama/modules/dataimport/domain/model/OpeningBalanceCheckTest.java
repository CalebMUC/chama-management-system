package ke.chama.modules.dataimport.domain.model;

import ke.chama.modules.dataimport.domain.model.OpeningBalanceSheets.AccountRow;
import ke.chama.modules.dataimport.domain.model.OpeningBalanceSheets.InvestmentRow;
import ke.chama.modules.dataimport.domain.model.OpeningBalanceSheets.LoanRow;
import ke.chama.modules.dataimport.domain.model.OpeningBalanceSheets.MemberRow;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OpeningBalanceCheckTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 7);
    private static final LocalDate AS_OF = LocalDate.of(2026, 10, 31);
    private static final UUID CHAIR = UUID.randomUUID();

    @Test
    void aGoodFileBecomesAPlanWithTheSurplusBroughtForward() {
        OpeningBalancePlan plan = check(sheets(
                List.of(member(2, "chair@mokica.test", 1_000_000, 0),
                        member(3, "alice@mokica.test", 2_000_000, 0),
                        member(4, "bob@mokica.test", 1_500_000, 50_000)),
                List.of(new LoanRow(2, "Alice@Mokica.test", 3_000_000L, 300_000L, 12, AS_OF.minusMonths(6), 1_100_000L, null)),
                List.of(new AccountRow(2, "Cash on hand", 200_000L), new AccountRow(3, "Bank account", 1_000_000L),
                        new AccountRow(4, "M-Pesa", 350_000L)),
                List.of(new InvestmentRow(2, "CIC money market fund", "Money market fund", "CIC", AS_OF.minusYears(1),
                        2_500_000L, 2_650_000L, 1_100))), AS_OF);

        assertThat(plan.importable()).as(plan.problems().toString()).isTrue();
        assertThat(plan.newMembers()).extracting(OpeningBalancePlan.NewMember::email)
                .containsExactly("alice@mokica.test", "bob@mokica.test");
        assertThat(plan.newMembers().getFirst().phone()).isEqualTo("+254712345678");
        assertThat(plan.balances().getFirst().existingUserId()).isEqualTo(CHAIR);
        assertThat(plan.savingsMinor()).isEqualTo(4_500_000);
        assertThat(plan.loansOutstandingMinor()).isEqualTo(2_200_000);
        assertThat(plan.investments().getFirst().type()).isEqualTo("MONEY_MARKET_FUND");
        // 15,500 cash + 25,000 investments + 22,000 loans + 500 penalties - 45,000 savings
        assertThat(plan.retainedSurplusMinor()).isEqualTo(1_800_000);
    }

    @Test
    void everyProblemIsReportedWithItsSheetRowAndColumn() {
        OpeningBalancePlan plan = check(sheets(
                List.of(new MemberRow(2, "not-an-email", "A", "B", "0712345678", AS_OF, Set.of(), 0L, 0L),
                        new MemberRow(3, "late@x.test", "Late", "Joiner", "0712345678", AS_OF.plusDays(1), Set.of(), 0L, 0L),
                        new MemberRow(4, "role@x.test", "Bad", "Role", "12345", AS_OF, Set.of("Boss"), -5L, 0L)),
                List.of(new LoanRow(2, "ghost@x.test", 100L, 0L, 48, AS_OF, 0L, null)),
                List.of(new AccountRow(2, "Piggy bank", 100L)),
                List.of(new InvestmentRow(2, "Shares", "Crypto", null, AS_OF, 100L, null, null))), AS_OF);

        assertThat(plan.importable()).isFalse();
        assertThat(plan.problems()).extracting(p -> p.sheet() + "!" + p.row() + ":" + p.column())
                .contains("Members!2:Email", "Members!3:Joined on", "Members!4:Phone", "Members!4:Roles",
                        "Members!4:Savings (KES)", "Loans!2:Member email", "Loans!2:Term (months)", "Accounts!2:Account",
                        "Investments!2:Type");
    }

    @Test
    void fullyRepaidLoansAndASecondLoanForTheSameMemberAreRefused() {
        OpeningBalancePlan plan = check(sheets(List.of(member(2, "alice@x.test", 100, 0)),
                List.of(new LoanRow(2, "alice@x.test", 1_000L, 100L, 6, AS_OF, 1_100L, null),
                        new LoanRow(3, "alice@x.test", 1_000L, 100L, 6, AS_OF, 0L, null)),
                List.of(), List.of()), AS_OF);

        assertThat(plan.problems()).extracting(Problem::message)
                .anyMatch(m -> m.contains("fully repaid"))
                .anyMatch(m -> m.contains("more than one loan"));
    }

    @Test
    void theLicenceLimitAndTheCutOverDateAreChecked() {
        OpeningBalanceSheets sheets = sheets(List.of(member(2, "a@x.test", 1, 0), member(3, "b@x.test", 1, 0)),
                List.of(), List.of(), List.of());
        OpeningBalanceCheck.Chama small = chama(2, Optional.of(AS_OF.minusDays(5)));

        OpeningBalancePlan plan = OpeningBalanceCheck.check(sheets, TODAY.plusDays(1), small);

        assertThat(plan.problems()).extracting(Problem::message)
                .anyMatch(m -> m.contains("cannot be in the future"))
                .anyMatch(m -> m.contains("licence allows 2 members"))
                .anyMatch(m -> m.contains("first contribution period"));
        assertThat(plan.problems()).filteredOn(p -> p.message().contains("first contribution period"))
                .allMatch(p -> !p.blocking());
    }

    @Test
    void anAccountWithoutASeatIsRefusedAndExistingMembersKeepTheirRoles() {
        OpeningBalancePlan plan = OpeningBalanceCheck.check(sheets(List.of(
                        new MemberRow(2, "pending@x.test", "P", "Q", "0712345678", AS_OF, Set.of(), 0L, 0L),
                        new MemberRow(3, "chair@mokica.test", null, null, null, null, Set.of("Treasurer"), 100L, 0L)),
                List.of(), List.of(), List.of()), AS_OF, new OpeningBalanceCheck.Chama(TODAY,
                Map.of("chair@mokica.test", CHAIR), Set.of("pending@x.test"), Map.of(CHAIR, 500L), Set.of(),
                Set.of("MEMBER", "TREASURER"), Set.of("BUSINESS"), 36, 100, Optional.empty(), "KES"));

        assertThat(plan.problems()).filteredOn(Problem::blocking).extracting(Problem::message)
                .anyMatch(m -> m.contains("pending application"));
        assertThat(plan.problems()).filteredOn(p -> !p.blocking()).extracting(Problem::message)
                .anyMatch(m -> m.contains("roles are not changed"))
                .anyMatch(m -> m.contains("already has savings of KES 5.00"));
    }

    // ---- helpers --------------------------------------------------------------------------------

    private static OpeningBalancePlan check(OpeningBalanceSheets sheets, LocalDate asOf) {
        return OpeningBalanceCheck.check(sheets, asOf, chama(100, Optional.empty()));
    }

    private static OpeningBalanceCheck.Chama chama(int maxMembers, Optional<LocalDate> firstDue) {
        return new OpeningBalanceCheck.Chama(TODAY, Map.of("chair@mokica.test", CHAIR), Set.of(), Map.of(), Set.of(),
                Set.of("MEMBER", "TREASURER", "SECRETARY", "CHAIRPERSON", "AUDITOR"),
                Set.of("MONEY_MARKET_FUND", "BUSINESS", "LAND"), 36, maxMembers, firstDue, "KES");
    }

    private static MemberRow member(int row, String email, long savings, long penalties) {
        return new MemberRow(row, email, "First", "Last", "0712345678", AS_OF.minusYears(2), Set.of(), savings, penalties);
    }

    private static OpeningBalanceSheets sheets(List<MemberRow> members, List<LoanRow> loans, List<AccountRow> accounts,
                                               List<InvestmentRow> investments) {
        return new OpeningBalanceSheets(members, loans, accounts, investments, List.of());
    }
}
