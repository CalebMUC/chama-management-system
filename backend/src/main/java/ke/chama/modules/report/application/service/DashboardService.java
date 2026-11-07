package ke.chama.modules.report.application.service;

import ke.chama.modules.account.api.MemberAccountSummary;
import ke.chama.modules.account.api.MemberAccounts;
import ke.chama.modules.account.api.SavingsProductSummary;
import ke.chama.modules.account.api.SavingsProducts;
import ke.chama.modules.chama.api.ChamaDirectory;
import ke.chama.modules.chama.api.ChamaSummary;
import ke.chama.modules.chama.api.LicenseSummary;
import ke.chama.modules.contribution.api.ContributionReports;
import ke.chama.modules.contribution.api.ContributionReports.ContributionMatrix;
import ke.chama.modules.contribution.api.ContributionReports.PeriodColumn;
import ke.chama.modules.contribution.api.ContributionReports.StandingSnapshot;
import ke.chama.modules.expense.api.ExpenseTotals;
import ke.chama.modules.expense.api.ExpenseTotals.ExpenseQueue;
import ke.chama.modules.investment.api.InvestmentPortfolio;
import ke.chama.modules.investment.api.InvestmentPortfolio.InvestmentSnapshot;
import ke.chama.modules.loan.api.LoanPortfolio;
import ke.chama.modules.loan.api.LoanPortfolio.LoanSnapshot;
import ke.chama.modules.member.api.MemberDirectory;
import ke.chama.modules.member.api.MemberDirectory.MembershipCounts;
import ke.chama.modules.member.api.MemberSummary;
import ke.chama.modules.report.application.port.in.DashboardUseCases;
import ke.chama.modules.report.application.port.in.DashboardViews.CashSection;
import ke.chama.modules.report.application.port.in.DashboardViews.ChamaDashboardView;
import ke.chama.modules.report.application.port.in.DashboardViews.ContributionsSection;
import ke.chama.modules.report.application.port.in.DashboardViews.InvestmentsSection;
import ke.chama.modules.report.application.port.in.DashboardViews.LoansSection;
import ke.chama.modules.report.application.port.in.DashboardViews.MemberDashboardView;
import ke.chama.modules.report.application.port.in.DashboardViews.MembersSection;
import ke.chama.modules.report.application.port.in.DashboardViews.MyAccount;
import ke.chama.modules.report.application.port.in.DashboardViews.MyContributionsSection;
import ke.chama.modules.report.application.port.in.DashboardViews.MyLoansSection;
import ke.chama.modules.report.application.port.in.DashboardViews.MySavingsSection;
import ke.chama.modules.report.application.port.in.DashboardViews.PendingSection;
import ke.chama.modules.report.application.port.in.DashboardViews.PerformanceSection;
import ke.chama.modules.report.application.port.in.DashboardViews.ProductBalance;
import ke.chama.modules.report.application.port.in.DashboardViews.SavingsSection;
import ke.chama.modules.report.domain.exception.ReportExceptions.MemberNotFoundException;
import ke.chama.modules.report.domain.model.FinancialStatements;
import ke.chama.modules.report.domain.model.FinancialStatements.AccountBalance;
import ke.chama.modules.report.domain.model.LoanAging;
import ke.chama.modules.transaction.api.Ledger;
import ke.chama.modules.transaction.api.Ledger.AccountTotal;
import ke.chama.modules.transaction.api.LedgerAccountKey;
import ke.chama.shared.tenancy.LicenseFeature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The dashboards. Every figure comes from the same sources as the reports (the ledger for money,
 * the modules' read APIs for the rest). Sections for features the chama's licence does not include
 * are left out (null). Read-write transactions only because contributions and accounts bring
 * themselves up to date before answering.
 */
@Service
@Transactional
public class DashboardService implements DashboardUseCases {

    static final int RECENT_ENTRIES = 10;

    private final ChamaDirectory chamas;
    private final MemberDirectory members;
    private final Ledger ledger;
    private final SavingsProducts savingsProducts;
    private final MemberAccounts memberAccounts;
    private final ContributionReports contributions;
    private final LoanPortfolio loans;
    private final InvestmentPortfolio investments;
    private final ExpenseTotals expenses;
    private final Clock clock;
    private final ZoneId zone;

    public DashboardService(ChamaDirectory chamas, MemberDirectory members, Ledger ledger,
                            SavingsProducts savingsProducts, MemberAccounts memberAccounts,
                            ContributionReports contributions, LoanPortfolio loans, InvestmentPortfolio investments,
                            ExpenseTotals expenses, Clock clock,
                            @Value("${chama.time-zone:Africa/Nairobi}") String timeZone) {
        this.chamas = chamas;
        this.members = members;
        this.ledger = ledger;
        this.savingsProducts = savingsProducts;
        this.memberAccounts = memberAccounts;
        this.contributions = contributions;
        this.loans = loans;
        this.investments = investments;
        this.expenses = expenses;
        this.clock = clock;
        this.zone = ZoneId.of(timeZone);
    }

    // ---- the chama ------------------------------------------------------------------------------

    @Override
    public ChamaDashboardView chama(UUID chamaId) {
        Set<LicenseFeature> features = features(chamaId);
        LocalDate today = today();
        Map<String, AccountTotal> books = ledger.accountTotals(chamaId, null, today).stream()
                .collect(Collectors.toMap(AccountTotal::code, Function.identity()));

        MembershipCounts counts = members.counts(chamaId);
        CashSection cash = cash(books);

        SavingsSection savings = null;
        ContributionsSection contributionsSection = null;
        if (features.contains(LicenseFeature.CONTRIBUTIONS)) {
            savings = savings(chamaId, books);
            contributionsSection = contributions(contributions.matrix(chamaId));
        }

        LoansSection loansSection = null;
        Integer loansAwaitingApproval = null;
        Integer loansAwaitingDisbursement = null;
        if (features.contains(LicenseFeature.LOANS)) {
            loansSection = loans(loans.loans(chamaId));
            loansAwaitingApproval = loansSection.awaitingApproval();
            loansAwaitingDisbursement = loansSection.awaitingDisbursement();
        }

        Long penalties = features.contains(LicenseFeature.PENALTIES)
                ? balanceOf(books, LedgerAccountKey.PENALTIES_RECEIVABLE) : null;

        InvestmentsSection investmentsSection = null;
        Long investmentsAwaitingApproval = null;
        if (features.contains(LicenseFeature.INVESTMENTS)) {
            List<InvestmentSnapshot> active = investments.fundedInvestments(chamaId).stream()
                    .filter(i -> "ACTIVE".equals(i.status())).toList();
            investmentsSection = new InvestmentsSection(active.size(),
                    active.stream().mapToLong(InvestmentSnapshot::investedMinor).sum(),
                    active.stream().mapToLong(InvestmentSnapshot::marketValueMinor).sum(),
                    active.stream().mapToLong(InvestmentSnapshot::incomeMinor).sum());
            investmentsAwaitingApproval = investments.awaitingApproval(chamaId);
        }

        Long expensesAwaitingApproval = null;
        Long expensesAwaitingPayment = null;
        if (features.contains(LicenseFeature.EXPENSES)) {
            ExpenseQueue queue = expenses.queue(chamaId);
            expensesAwaitingApproval = queue.pendingApproval();
            expensesAwaitingPayment = queue.approvedUnpaid();
        }

        PendingSection pending = new PendingSection(counts.pendingApproval(), loansAwaitingApproval,
                loansAwaitingDisbursement, expensesAwaitingApproval, expensesAwaitingPayment,
                investmentsAwaitingApproval);
        return new ChamaDashboardView(chamaName(chamaId), today, currency(chamaId), names(features),
                new MembersSection(counts.active(), counts.suspended(), counts.pendingApproval()), cash, savings,
                contributionsSection, loansSection, penalties, investmentsSection,
                performance(chamaId, today.withDayOfMonth(1), today), performance(chamaId, today.withDayOfYear(1), today),
                pending, ledger.recentEntries(chamaId, RECENT_ENTRIES), clock.instant());
    }

    private static CashSection cash(Map<String, AccountTotal> books) {
        long cashOnHand = balanceOf(books, LedgerAccountKey.CASH);
        long bank = balanceOf(books, LedgerAccountKey.BANK);
        long mobile = balanceOf(books, LedgerAccountKey.MOBILE_MONEY);
        return new CashSection(cashOnHand, bank, mobile, cashOnHand + bank + mobile);
    }

    /** Active products, and inactive ones that still hold money. */
    private SavingsSection savings(UUID chamaId, Map<String, AccountTotal> books) {
        List<ProductBalance> products = savingsProducts.products(chamaId).stream()
                .map(p -> Map.entry(p, books.containsKey(p.ledgerAccountCode())
                        ? books.get(p.ledgerAccountCode()).balanceMinor() : 0L))
                .filter(e -> e.getKey().active() || e.getValue() != 0)
                .map(e -> new ProductBalance(e.getKey().id(), e.getKey().code(), e.getKey().name(), e.getValue()))
                .toList();
        return new SavingsSection(products.stream().mapToLong(ProductBalance::balanceMinor).sum(), products);
    }

    /** The latest period, and arrears across every period. */
    static ContributionsSection contributions(ContributionMatrix matrix) {
        long arrears = matrix.members().stream().mapToLong(r -> r.standing().arrearsMinor()).sum();
        int inArrears = (int) matrix.members().stream().filter(r -> r.standing().arrearsMinor() > 0).count();
        if (matrix.periods().isEmpty()) {
            return new ContributionsSection(null, null, 0, 0, 0, 0, 0, arrears, inArrears);
        }
        PeriodColumn current = matrix.periods().getLast();
        long rate = current.expectedMinor() == 0 ? 0
                : Math.round(current.collectedMinor() * 10_000.0 / current.expectedMinor());
        return new ContributionsSection(current.key(), current.dueDate(), current.expectedMinor(),
                current.collectedMinor(), rate, current.membersPaid(), current.membersLate(), arrears, inArrears);
    }

    static LoansSection loans(List<LoanSnapshot> all) {
        LoanAging.Summary aging = LoanAging.of(all.stream().filter(LoanSnapshot::active)
                .map(l -> new LoanAging.LoanAmounts(l.outstandingMinor(), l.arrearsMinor(), l.daysInArrears()))
                .toList());
        int awaitingApproval = (int) all.stream()
                .filter(l -> "PENDING_GUARANTORS".equals(l.status()) || "PENDING_APPROVAL".equals(l.status())).count();
        int awaitingDisbursement = (int) all.stream().filter(l -> "APPROVED".equals(l.status())).count();
        return new LoansSection(aging.loans(), aging.outstandingMinor(), aging.arrearsMinor(), aging.loansInArrears(),
                aging.par30BasisPoints(), awaitingApproval, awaitingDisbursement);
    }

    private PerformanceSection performance(UUID chamaId, LocalDate from, LocalDate to) {
        FinancialStatements.Performance p = FinancialStatements.performance(ledger.accountTotals(chamaId, from, to)
                .stream().map(t -> new AccountBalance(t.code(), t.name(), t.type(), t.balanceMinor())).toList());
        return new PerformanceSection(from, to, p.income().totalMinor(), p.expenses().totalMinor(), p.surplusMinor());
    }

    // ---- one member -------------------------------------------------------------------------------

    @Override
    public MemberDashboardView member(UUID chamaId, UUID memberUserId) {
        MemberSummary member = members.seatedMember(chamaId, memberUserId).orElseThrow(MemberNotFoundException::new);
        Set<LicenseFeature> features = features(chamaId);

        MySavingsSection savings = null;
        MyContributionsSection myContributions = null;
        if (features.contains(LicenseFeature.CONTRIBUTIONS)) {
            memberAccounts.openMandatoryAccounts(chamaId);
            Map<UUID, SavingsProductSummary> products = savingsProducts.products(chamaId).stream()
                    .collect(Collectors.toMap(SavingsProductSummary::id, Function.identity()));
            List<MyAccount> accounts = memberAccounts.accountsOf(chamaId, memberUserId).stream()
                    .map(a -> myAccount(chamaId, memberUserId, a, products.get(a.productId())))
                    .toList();
            savings = new MySavingsSection(accounts.stream().mapToLong(MyAccount::balanceMinor).sum(), accounts);
            StandingSnapshot s = contributions.standing(chamaId, memberUserId);
            myContributions = new MyContributionsSection(s.totalPaidMinor(), s.arrearsMinor(), s.advanceMinor(),
                    s.latePeriods(), s.latestPeriod(), s.latestPeriodStatus());
        }

        MyLoansSection myLoans = null;
        if (features.contains(LicenseFeature.LOANS)) {
            List<LoanSnapshot> mine = loans.loansOf(chamaId, memberUserId);
            List<LoanSnapshot> active = mine.stream().filter(LoanSnapshot::active).toList();
            LoanSnapshot next = active.stream().filter(l -> l.nextDueDate() != null)
                    .min(Comparator.comparing(LoanSnapshot::nextDueDate)).orElse(null);
            myLoans = new MyLoansSection(active.size(), (int) mine.stream().filter(LoanSnapshot::inProgress).count(),
                    active.stream().mapToLong(LoanSnapshot::outstandingMinor).sum(),
                    active.stream().mapToLong(LoanSnapshot::arrearsMinor).sum(),
                    next == null ? null : next.nextDueDate(), next == null ? 0 : next.nextDueMinor(),
                    loans.pendingGuaranteeRequests(chamaId, memberUserId));
        }

        Long penalties = features.contains(LicenseFeature.PENALTIES)
                ? ledger.memberBalance(chamaId, memberUserId, LedgerAccountKey.PENALTIES_RECEIVABLE).minorUnits()
                : null;
        String name = member.fullName().trim();
        return new MemberDashboardView(chamaName(chamaId), memberUserId, name.isEmpty() ? member.email() : name,
                member.memberNumber(), currency(chamaId), names(features), savings, myContributions, myLoans, penalties,
                clock.instant());
    }

    private MyAccount myAccount(UUID chamaId, UUID memberUserId, MemberAccountSummary a, SavingsProductSummary p) {
        long balance = ledger.memberBalance(chamaId, memberUserId, p.ledgerAccountCode()).minorUnits();
        return new MyAccount(a.id(), a.accountNumber(), p.code(), p.name(), a.active(), balance);
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** The licence's features; a chama created before licensing has every feature. */
    private Set<LicenseFeature> features(UUID chamaId) {
        return chamas.license(chamaId).map(LicenseSummary::features)
                .map(f -> f.isEmpty() ? EnumSet.noneOf(LicenseFeature.class) : EnumSet.copyOf(f))
                .orElseGet(() -> EnumSet.allOf(LicenseFeature.class));
    }

    private static List<String> names(Set<LicenseFeature> features) {
        return features.stream().map(Enum::name).sorted().toList();
    }

    private static long balanceOf(Map<String, AccountTotal> books, LedgerAccountKey key) {
        return books.values().stream().filter(t -> Objects.equals(t.systemKey(), key))
                .mapToLong(AccountTotal::balanceMinor).findFirst().orElse(0);
    }

    private String chamaName(UUID chamaId) {
        return chamas.find(chamaId).map(ChamaSummary::name).orElse(null);
    }

    private String currency(UUID chamaId) {
        return chamas.find(chamaId).map(ChamaSummary::currency).orElse("KES");
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(zone));
    }
}
