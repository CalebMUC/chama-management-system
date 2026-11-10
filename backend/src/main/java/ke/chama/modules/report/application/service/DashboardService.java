package ke.chama.modules.report.application.service;

import ke.chama.modules.account.api.MemberAccountSummary;
import ke.chama.modules.account.api.MemberAccounts;
import ke.chama.modules.account.api.SavingsProductSummary;
import ke.chama.modules.account.api.SavingsProducts;
import ke.chama.modules.chama.api.ChamaDirectory;
import ke.chama.modules.chama.api.ChamaSummary;
import ke.chama.modules.chama.api.LicenseSummary;
import ke.chama.modules.contribution.api.ContributionReports;
import ke.chama.modules.contribution.api.ContributionReports.Cell;
import ke.chama.modules.contribution.api.ContributionReports.ContributionMatrix;
import ke.chama.modules.contribution.api.ContributionReports.MemberRow;
import ke.chama.modules.contribution.api.ContributionReports.PaymentTotal;
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
import ke.chama.modules.member.api.MemberPermissions;
import ke.chama.modules.member.api.MemberSummary;
import ke.chama.modules.penalty.api.PenaltyReports;
import ke.chama.modules.penalty.api.PenaltyReports.MemberPenalties;
import ke.chama.modules.penalty.api.PenaltyReports.PenaltySummary;
import ke.chama.modules.report.application.port.in.DashboardUseCases;
import ke.chama.modules.report.application.port.in.DashboardViews.CashSection;
import ke.chama.modules.report.application.port.in.DashboardViews.ChamaDashboardView;
import ke.chama.modules.report.application.port.in.DashboardViews.ChamaOverviewView;
import ke.chama.modules.report.application.port.in.DashboardViews.ContributionsSection;
import ke.chama.modules.report.application.port.in.DashboardViews.InvestmentPerformance;
import ke.chama.modules.report.application.port.in.DashboardViews.InvestmentsSection;
import ke.chama.modules.report.application.port.in.DashboardViews.KeyFigures;
import ke.chama.modules.report.application.port.in.DashboardViews.LoansSection;
import ke.chama.modules.report.application.port.in.DashboardViews.MemberAmount;
import ke.chama.modules.report.application.port.in.DashboardViews.MemberDashboardView;
import ke.chama.modules.report.application.port.in.DashboardViews.MembersSection;
import ke.chama.modules.report.application.port.in.DashboardViews.MonthTrend;
import ke.chama.modules.report.application.port.in.DashboardViews.MyAccount;
import ke.chama.modules.report.application.port.in.DashboardViews.MyContributionsSection;
import ke.chama.modules.report.application.port.in.DashboardViews.MyLoan;
import ke.chama.modules.report.application.port.in.DashboardViews.MyLoansSection;
import ke.chama.modules.report.application.port.in.DashboardViews.MyPenaltiesSection;
import ke.chama.modules.report.application.port.in.DashboardViews.MyPenalty;
import ke.chama.modules.report.application.port.in.DashboardViews.MySavingsSection;
import ke.chama.modules.report.application.port.in.DashboardViews.MySubmissionsSection;
import ke.chama.modules.report.application.port.in.DashboardViews.PenaltiesSection;
import ke.chama.modules.report.application.port.in.DashboardViews.PendingSection;
import ke.chama.modules.report.application.port.in.DashboardViews.PerformanceSection;
import ke.chama.modules.report.application.port.in.DashboardViews.ProductBalance;
import ke.chama.modules.report.application.port.in.DashboardViews.SavingsSection;
import ke.chama.modules.report.application.port.in.DashboardViews.TopInvestmentsView;
import ke.chama.modules.report.application.port.in.DashboardViews.TopMember;
import ke.chama.modules.report.application.port.in.DashboardViews.TopMembersView;
import ke.chama.modules.report.application.port.in.DashboardViews.TrendsView;
import ke.chama.modules.report.domain.exception.ReportExceptions.InvalidPeriodException;
import ke.chama.modules.report.domain.exception.ReportExceptions.MemberNotFoundException;
import ke.chama.modules.report.domain.model.FinancialStatements;
import ke.chama.modules.report.domain.model.FinancialStatements.AccountBalance;
import ke.chama.modules.report.domain.model.LoanAging;
import ke.chama.modules.submission.api.PaymentSubmissionQueue;
import ke.chama.modules.submission.api.PaymentSubmissionQueue.PendingCounts;
import ke.chama.modules.transaction.api.Ledger;
import ke.chama.modules.transaction.api.Ledger.AccountTotal;
import ke.chama.modules.transaction.api.LedgerAccountKey;
import ke.chama.shared.security.ChamaPermission;
import ke.chama.shared.tenancy.LicenseFeature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
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
    static final int TOP = 5;
    static final int MEMBER_TOP_INVESTMENTS = 3;
    static final int TREND_MONTHS = 6;
    static final int MAX_TREND_MONTHS = 24;
    static final int MAX_LIMIT = 50;
    static final long FULL_RATE = 10_000;

    private final ChamaDirectory chamas;
    private final MemberDirectory members;
    private final MemberPermissions permissions;
    private final Ledger ledger;
    private final SavingsProducts savingsProducts;
    private final MemberAccounts memberAccounts;
    private final ContributionReports contributions;
    private final LoanPortfolio loans;
    private final InvestmentPortfolio investments;
    private final ExpenseTotals expenses;
    private final PenaltyReports penalties;
    private final PaymentSubmissionQueue submissions;
    private final Clock clock;
    private final ZoneId zone;

    public DashboardService(ChamaDirectory chamas, MemberDirectory members, MemberPermissions permissions, Ledger ledger,
                            SavingsProducts savingsProducts, MemberAccounts memberAccounts,
                            ContributionReports contributions, LoanPortfolio loans, InvestmentPortfolio investments,
                            ExpenseTotals expenses, PenaltyReports penalties, PaymentSubmissionQueue submissions,
                            Clock clock, @Value("${chama.time-zone:Africa/Nairobi}") String timeZone) {
        this.chamas = chamas;
        this.members = members;
        this.permissions = permissions;
        this.ledger = ledger;
        this.savingsProducts = savingsProducts;
        this.memberAccounts = memberAccounts;
        this.contributions = contributions;
        this.loans = loans;
        this.investments = investments;
        this.expenses = expenses;
        this.penalties = penalties;
        this.submissions = submissions;
        this.clock = clock;
        this.zone = ZoneId.of(timeZone);
    }

    // ---- the chama ------------------------------------------------------------------------------

    @Override
    public ChamaDashboardView chama(UUID chamaId) {
        return build(chamaId, true);
    }

    @Override
    public ChamaOverviewView overview(UUID chamaId, UUID viewerUserId) {
        ChamaDashboardView d = build(chamaId, false);
        boolean seesInvestments = permissions.permissionsOf(chamaId, viewerUserId).contains(ChamaPermission.INVESTMENT_VIEW);
        KeyFigures k = d.keyFigures();
        KeyFigures keyFigures = seesInvestments ? k : new KeyFigures(k.totalContributionsMinor(),
                k.contributionsYearToDateMinor(), k.totalSavingsMinor(), k.cashMinor(), k.loansOutstandingMinor(), null,
                k.penaltiesOutstandingMinor(), k.totalAssetsMinor(), k.netWorthMinor());
        return new ChamaOverviewView(d.chamaName(), d.asOf(), d.currency(), d.features(), keyFigures, d.members(),
                d.cash(), d.savings(), d.contributions(), d.loans(), d.penalties(),
                seesInvestments ? d.investments() : null, seesInvestments ? d.topInvestments() : null,
                d.thisMonth(), d.yearToDate(), d.trends(), d.generatedAt());
    }

    /**
     * @param forOfficials false leaves out (null / empty) everything that names a member or is an
     *                     official's work: top contributors, who owes penalties, the work queue and
     *                     ledger entries, so a member's overview never computes them
     */
    private ChamaDashboardView build(UUID chamaId, boolean forOfficials) {
        Set<LicenseFeature> features = features(chamaId);
        LocalDate today = today();
        LocalDate yearStart = today.withDayOfYear(1);
        LocalDate monthStart = today.withDayOfMonth(1);
        List<AccountTotal> totals = ledger.accountTotals(chamaId, null, today);
        Map<String, AccountTotal> books = totals.stream().collect(Collectors.toMap(AccountTotal::code, Function.identity()));
        Map<UUID, MemberSummary> seated = seatedById(chamaId);

        MembershipCounts counts = members.counts(chamaId);
        CashSection cash = cash(books);

        SavingsSection savings = null;
        ContributionsSection contributionsSection = null;
        List<TopMember> topMembers = null;
        Map<YearMonth, PaymentTotal> byMonth = null;
        if (features.contains(LicenseFeature.CONTRIBUTIONS)) {
            savings = savings(chamaId, books);
            ContributionMatrix matrix = contributions.matrix(chamaId);
            Map<UUID, PaymentTotal> allTime = contributions.paymentsByMember(chamaId, null, null);
            Map<UUID, PaymentTotal> yearToDate = contributions.paymentsByMember(chamaId, yearStart, today);
            Map<UUID, PaymentTotal> thisMonth = contributions.paymentsByMember(chamaId, monthStart, today);
            contributionsSection = contributions(matrix, sum(allTime), sum(thisMonth), sum(yearToDate));
            topMembers = forOfficials ? rankMembers(chamaId, matrix, yearToDate, seated, TOP) : null;
            byMonth = contributions.paymentsByMonth(chamaId, trendStart(today, TREND_MONTHS), today);
        }

        LoansSection loansSection = null;
        List<LoanSnapshot> loanBook = List.of();
        Integer loansAwaitingApproval = null;
        Integer loansAwaitingDisbursement = null;
        if (features.contains(LicenseFeature.LOANS)) {
            loanBook = loans.loans(chamaId);
            loansSection = loans(loanBook, today);
            loansAwaitingApproval = loansSection.awaitingApproval();
            loansAwaitingDisbursement = loansSection.awaitingDisbursement();
        }

        PenaltiesSection penaltiesSection = null;
        if (features.contains(LicenseFeature.PENALTIES)) {
            penaltiesSection = penalties(penalties.summary(chamaId, yearStart, today, forOfficials ? TOP : 0), seated);
        }

        InvestmentsSection investmentsSection = null;
        List<InvestmentPerformance> topInvestments = null;
        Long investmentsAwaitingApproval = null;
        if (features.contains(LicenseFeature.INVESTMENTS)) {
            List<InvestmentSnapshot> funded = investments.fundedInvestments(chamaId);
            investmentsSection = investments(funded, today);
            topInvestments = rankInvestments(funded, TOP);
            investmentsAwaitingApproval = forOfficials ? investments.awaitingApproval(chamaId) : null;
        }

        PendingSection pending = null;
        if (forOfficials) {
            Long expensesAwaitingApproval = null;
            Long expensesAwaitingPayment = null;
            if (features.contains(LicenseFeature.EXPENSES)) {
                ExpenseQueue queue = expenses.queue(chamaId);
                expensesAwaitingApproval = queue.pendingApproval();
                expensesAwaitingPayment = queue.approvedUnpaid();
            }
            PendingCounts reported = submissions.pending(chamaId);
            pending = new PendingSection(counts.pendingApproval(), loansAwaitingApproval,
                    loansAwaitingDisbursement, expensesAwaitingApproval, expensesAwaitingPayment,
                    investmentsAwaitingApproval, reported.total(), reported.contributions(), reported.penaltyPayments(),
                    reported.loanRepayments());
        }

        FinancialStatements.Position position = FinancialStatements.position(totals.stream()
                .map(t -> new AccountBalance(t.code(), t.name(), t.type(), t.balanceMinor())).toList());
        KeyFigures keyFigures = new KeyFigures(
                contributionsSection == null ? null : contributionsSection.totalCollectedMinor(),
                contributionsSection == null ? null : contributionsSection.collectedYearToDateMinor(),
                savings == null ? null : savings.totalMinor(),
                cash.totalMinor(),
                loansSection == null ? null : loansSection.outstandingMinor(),
                investmentsSection == null ? null : investmentsSection.marketValueMinor(),
                penaltiesSection == null ? null : penaltiesSection.outstandingMinor(),
                position.assets().totalMinor(),
                position.assets().totalMinor() - position.liabilities().totalMinor());

        return new ChamaDashboardView(chamaName(chamaId), today, currency(chamaId), names(features), keyFigures,
                members(counts, seated.values(), today), cash, savings, contributionsSection, loansSection,
                penaltiesSection == null ? null : penaltiesSection.outstandingMinor(), penaltiesSection,
                investmentsSection, topMembers, topInvestments,
                performance(chamaId, monthStart, today), performance(chamaId, yearStart, today),
                trends(chamaId, today, TREND_MONTHS, byMonth, features.contains(LicenseFeature.LOANS) ? loanBook : null),
                pending, forOfficials ? ledger.recentEntries(chamaId, RECENT_ENTRIES) : null, clock.instant());
    }

    @Override
    public TopMembersView topMembers(UUID chamaId, LocalDate from, LocalDate to, int limit) {
        LocalDate today = today();
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? end.withDayOfYear(1) : from;
        if (start.isAfter(end)) {
            throw new InvalidPeriodException("The start date must not be after the end date");
        }
        ContributionMatrix matrix = contributions.matrix(chamaId);
        List<TopMember> ranked = rankMembers(chamaId, matrix, contributions.paymentsByMember(chamaId, start, end),
                seatedById(chamaId), clampLimit(limit));
        return new TopMembersView(start, end, currency(chamaId), ranked);
    }

    @Override
    @Transactional(readOnly = true)
    public TopInvestmentsView topInvestments(UUID chamaId, int limit) {
        return new TopInvestmentsView(currency(chamaId),
                rankInvestments(investments.fundedInvestments(chamaId), clampLimit(limit)));
    }

    @Override
    @Transactional(readOnly = true)
    public TrendsView trends(UUID chamaId, int months) {
        if (months < 1 || months > MAX_TREND_MONTHS) {
            throw new InvalidPeriodException("Choose between 1 and " + MAX_TREND_MONTHS + " months");
        }
        Set<LicenseFeature> features = features(chamaId);
        LocalDate today = today();
        Map<YearMonth, PaymentTotal> byMonth = features.contains(LicenseFeature.CONTRIBUTIONS)
                ? contributions.paymentsByMonth(chamaId, trendStart(today, months), today) : null;
        List<LoanSnapshot> loanBook = features.contains(LicenseFeature.LOANS) ? loans.loans(chamaId) : null;
        return new TrendsView(currency(chamaId), trends(chamaId, today, months, byMonth, loanBook));
    }

    private static CashSection cash(Map<String, AccountTotal> books) {
        long cashOnHand = balanceOf(books, LedgerAccountKey.CASH);
        long bank = balanceOf(books, LedgerAccountKey.BANK);
        long mobile = balanceOf(books, LedgerAccountKey.MOBILE_MONEY);
        return new CashSection(cashOnHand, bank, mobile, cashOnHand + bank + mobile);
    }

    private MembersSection members(MembershipCounts counts, Iterable<MemberSummary> seated, LocalDate today) {
        int thisMonth = 0;
        int thisYear = 0;
        for (MemberSummary m : seated) {
            if (m.joinedAt() == null) {
                continue;
            }
            LocalDate joined = LocalDate.ofInstant(m.joinedAt(), zone);
            if (joined.getYear() == today.getYear()) {
                thisYear++;
                if (joined.getMonth() == today.getMonth()) {
                    thisMonth++;
                }
            }
        }
        return new MembersSection(counts.active(), counts.suspended(), counts.pendingApproval(), thisMonth, thisYear);
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

    /** The latest period, arrears across every period, and what was paid in. */
    static ContributionsSection contributions(ContributionMatrix matrix, PaymentTotal allTime, PaymentTotal thisMonth,
                                              PaymentTotal yearToDate) {
        long arrears = matrix.members().stream().mapToLong(r -> r.standing().arrearsMinor()).sum();
        int inArrears = (int) matrix.members().stream().filter(r -> r.standing().arrearsMinor() > 0).count();
        long[] dues = new long[2];
        matrix.members().forEach(r -> {
            long[] d = dues(matrix.periods(), r);
            dues[0] += d[0];
            dues[1] += d[1];
        });
        long compliance = rate(dues[1], dues[0]);
        if (matrix.periods().isEmpty()) {
            return new ContributionsSection(null, null, 0, 0, 0, 0, 0, arrears, inArrears, allTime.totalMinor(),
                    thisMonth.totalMinor(), yearToDate.totalMinor(), thisMonth.count(), compliance);
        }
        PeriodColumn current = matrix.periods().getLast();
        long rate = current.expectedMinor() == 0 ? 0
                : Math.round(current.collectedMinor() * 10_000.0 / current.expectedMinor());
        return new ContributionsSection(current.key(), current.dueDate(), current.expectedMinor(),
                current.collectedMinor(), rate, current.membersPaid(), current.membersLate(), arrears, inArrears,
                allTime.totalMinor(), thisMonth.totalMinor(), yearToDate.totalMinor(), thisMonth.count(), compliance);
    }

    /** {dues that have arrived, of which fully paid} for one member. */
    static long[] dues(List<PeriodColumn> periods, MemberRow row) {
        long due = 0;
        long paid = 0;
        for (int i = 0; i < periods.size() && i < row.cells().size(); i++) {
            Cell cell = row.cells().get(i);
            if (cell == null || !periods.get(i).due() || cell.dueMinor() == 0) {
                continue;
            }
            due++;
            if ("PAID".equals(cell.status())) {
                paid++;
            }
        }
        return new long[]{due, paid};
    }

    /** Per 10,000; nothing due yet counts as fully compliant. */
    static long rate(long part, long whole) {
        return whole == 0 ? FULL_RATE : Math.round(part * 10_000.0 / whole);
    }

    /**
     * Members with a contribution in the window, most contributed first; ties go to whoever paid
     * more of their dues in full, then the lower member number.
     */
    private List<TopMember> rankMembers(UUID chamaId, ContributionMatrix matrix, Map<UUID, PaymentTotal> window,
                                        Map<UUID, MemberSummary> seated, int limit) {
        record Candidate(MemberSummary member, PaymentTotal paid, long compliance, StandingSnapshot standing) {
        }
        List<Candidate> candidates = new ArrayList<>();
        for (MemberRow row : matrix.members()) {
            PaymentTotal paid = window.getOrDefault(row.memberUserId(), PaymentTotal.NONE);
            MemberSummary member = seated.get(row.memberUserId());
            if (member == null || paid.totalMinor() <= 0) {
                continue;
            }
            long[] d = dues(matrix.periods(), row);
            candidates.add(new Candidate(member, paid, rate(d[1], d[0]), row.standing()));
        }
        List<Candidate> top = candidates.stream()
                .sorted(Comparator.comparingLong((Candidate c) -> c.paid().totalMinor()).reversed()
                        .thenComparing(Comparator.comparingLong(Candidate::compliance).reversed())
                        .thenComparing(c -> c.member().memberNumber(), Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(limit)
                .toList();
        List<SavingsProductSummary> products = savingsProducts.products(chamaId);
        List<TopMember> result = new ArrayList<>();
        for (int i = 0; i < top.size(); i++) {
            Candidate c = top.get(i);
            long savings = products.stream()
                    .mapToLong(p -> ledger.memberBalance(chamaId, c.member().userId(), p.ledgerAccountCode()).minorUnits())
                    .sum();
            result.add(new TopMember(i + 1, c.member().userId(), name(c.member()), c.member().memberNumber(),
                    c.paid().totalMinor(), c.paid().count(), c.compliance(), c.standing().arrearsMinor(),
                    c.standing().latePeriods(), savings));
        }
        return result;
    }

    static LoansSection loans(List<LoanSnapshot> all, LocalDate today) {
        LoanAging.Summary aging = LoanAging.of(all.stream().filter(LoanSnapshot::active)
                .map(l -> new LoanAging.LoanAmounts(l.outstandingMinor(), l.arrearsMinor(), l.daysInArrears()))
                .toList());
        int awaitingApproval = (int) all.stream()
                .filter(l -> "PENDING_GUARANTORS".equals(l.status()) || "PENDING_APPROVAL".equals(l.status())).count();
        int awaitingDisbursement = (int) all.stream().filter(l -> "APPROVED".equals(l.status())).count();
        List<LoanSnapshot> disbursed = all.stream().filter(l -> l.disbursedOn() != null).toList();
        List<LoanSnapshot> thisYear = disbursed.stream()
                .filter(l -> !l.disbursedOn().isBefore(today.withDayOfYear(1))).toList();
        LocalDate weekAhead = today.plusDays(7);
        List<LoanSnapshot> dueSoon = all.stream().filter(LoanSnapshot::active)
                .filter(l -> l.nextDueDate() != null && !l.nextDueDate().isBefore(today) && !l.nextDueDate().isAfter(weekAhead))
                .toList();
        return new LoansSection(aging.loans(), aging.outstandingMinor(), aging.arrearsMinor(), aging.loansInArrears(),
                aging.par30BasisPoints(), awaitingApproval, awaitingDisbursement, aging.buckets(),
                thisYear.size(), thisYear.stream().mapToLong(LoanSnapshot::principalMinor).sum(),
                disbursed.stream().mapToLong(LoanSnapshot::principalMinor).sum(),
                disbursed.stream().mapToLong(LoanSnapshot::repaidMinor).sum(),
                (int) all.stream().filter(l -> "REPAID".equals(l.status())).count(),
                all.stream().filter(LoanSnapshot::active).mapToLong(LoanSnapshot::interestMinor).sum(),
                dueSoon.size(), dueSoon.stream().mapToLong(LoanSnapshot::nextDueMinor).sum());
    }

    private static PenaltiesSection penalties(PenaltySummary s, Map<UUID, MemberSummary> seated) {
        List<MemberAmount> top = s.topOwing().stream().map(o -> {
            MemberSummary m = seated.get(o.memberUserId());
            return new MemberAmount(o.memberUserId(), name(m), m == null ? null : m.memberNumber(), o.outstandingMinor(),
                    o.penalties());
        }).toList();
        return new PenaltiesSection(s.outstandingMinor(), s.outstandingCount(), s.membersOwing(), s.assessedMinor(),
                s.lateContributionMinor(), s.finesMinor(), s.collectedMinor(), s.waivedMinor(), top);
    }

    static InvestmentsSection investments(List<InvestmentSnapshot> funded, LocalDate today) {
        List<InvestmentSnapshot> active = funded.stream().filter(InvestmentSnapshot::active).toList();
        long book = active.stream().mapToLong(InvestmentSnapshot::bookValueMinor).sum();
        long market = active.stream().mapToLong(InvestmentSnapshot::marketValueMinor).sum();
        long totalReturn = funded.stream().mapToLong(InvestmentSnapshot::totalReturnMinor).sum();
        long investedEver = funded.stream().mapToLong(InvestmentSnapshot::investedMinor).sum();
        LocalDate horizon = today.plusDays(30);
        int maturing = (int) active.stream().filter(i -> i.maturityDate() != null
                && !i.maturityDate().isBefore(today) && !i.maturityDate().isAfter(horizon)).count();
        return new InvestmentsSection(active.size(), active.stream().mapToLong(InvestmentSnapshot::investedMinor).sum(),
                market, active.stream().mapToLong(InvestmentSnapshot::incomeMinor).sum(), book, market - book,
                totalReturn, investedEver == 0 ? 0 : Math.round(totalReturn * 10_000.0 / investedEver),
                (int) funded.stream().filter(InvestmentSnapshot::profitable).count(),
                funded.size() - active.size(), maturing);
    }

    /** Best return first; ties go to the larger profit. */
    static List<InvestmentPerformance> rankInvestments(List<InvestmentSnapshot> funded, int limit) {
        List<InvestmentSnapshot> ranked = funded.stream()
                .sorted(Comparator.comparingInt(InvestmentSnapshot::returnBasisPoints).reversed()
                        .thenComparing(Comparator.comparingLong(InvestmentSnapshot::totalReturnMinor).reversed()))
                .limit(limit)
                .toList();
        List<InvestmentPerformance> result = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            InvestmentSnapshot s = ranked.get(i);
            result.add(new InvestmentPerformance(i + 1, s.id(), s.name(), s.type(), s.institution(), s.status(),
                    s.investedMinor(), s.marketValueMinor(), s.incomeMinor(), s.expensesMinor(), s.totalReturnMinor(),
                    s.returnBasisPoints(), s.annualisedReturnBasisPoints(), s.daysHeld(), s.profitable(), s.fundedOn(),
                    s.maturityDate()));
        }
        return result;
    }

    /**
     * @param byMonth  contributions per month, or null without CONTRIBUTIONS
     * @param loanBook every loan, or null without LOANS
     */
    private List<MonthTrend> trends(UUID chamaId, LocalDate today, int months, Map<YearMonth, PaymentTotal> byMonth,
                                    List<LoanSnapshot> loanBook) {
        List<MonthTrend> result = new ArrayList<>();
        YearMonth current = YearMonth.from(today);
        for (YearMonth month = current.minusMonths(months - 1L); !month.isAfter(current); month = month.plusMonths(1)) {
            LocalDate end = month.equals(current) ? today : month.atEndOfMonth();
            PerformanceSection p = performance(chamaId, month.atDay(1), end);
            PaymentTotal paid = byMonth == null ? null : byMonth.getOrDefault(month, PaymentTotal.NONE);
            YearMonth m = month;
            Long disbursed = loanBook == null ? null : loanBook.stream()
                    .filter(l -> l.disbursedOn() != null && YearMonth.from(l.disbursedOn()).equals(m))
                    .mapToLong(LoanSnapshot::principalMinor).sum();
            result.add(new MonthTrend(month, paid == null ? null : paid.totalMinor(), paid == null ? null : paid.count(),
                    disbursed, p.incomeMinor(), p.expensesMinor(), p.surplusMinor()));
        }
        return result;
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
        LocalDate today = today();

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
            myContributions = myContributions(chamaId, memberUserId, today);
        }

        MyLoansSection myLoans = null;
        if (features.contains(LicenseFeature.LOANS)) {
            myLoans = myLoans(loans.loansOf(chamaId, memberUserId), loans.pendingGuaranteeRequests(chamaId, memberUserId));
        }

        MyPenaltiesSection myPenalties = null;
        if (features.contains(LicenseFeature.PENALTIES)) {
            MemberPenalties p = penalties.ofMember(chamaId, memberUserId);
            myPenalties = new MyPenaltiesSection(p.outstandingMinor(), p.outstanding().size(), p.paidMinor(),
                    p.waivedMinor(), p.outstanding().stream().map(o -> new MyPenalty(o.penaltyId(), o.kind(),
                    o.description(), o.assessedOn(), o.amountMinor(), o.outstandingMinor())).toList());
        }

        List<InvestmentPerformance> topInvestments = null;
        if (features.contains(LicenseFeature.INVESTMENTS)
                && permissions.permissionsOf(chamaId, memberUserId).contains(ChamaPermission.INVESTMENT_VIEW)) {
            topInvestments = rankInvestments(investments.fundedInvestments(chamaId), MEMBER_TOP_INVESTMENTS);
        }

        long net = (savings == null ? 0 : savings.totalMinor()) - (myLoans == null ? 0 : myLoans.outstandingMinor())
                - (myPenalties == null ? 0 : myPenalties.outstandingMinor());
        String name = name(member);
        return new MemberDashboardView(chamaName(chamaId), memberUserId, name, member.memberNumber(), currency(chamaId),
                names(features), net, savings, myContributions, myLoans,
                myPenalties == null ? null : myPenalties.outstandingMinor(), myPenalties,
                new MySubmissionsSection(submissions.pendingOf(chamaId, memberUserId)), topInvestments, clock.instant());
    }

    private MyContributionsSection myContributions(UUID chamaId, UUID memberUserId, LocalDate today) {
        ContributionMatrix matrix = contributions.matrix(chamaId);
        MemberRow row = matrix.members().stream().filter(r -> r.memberUserId().equals(memberUserId)).findFirst()
                .orElse(null);
        StandingSnapshot s = row == null ? contributions.standing(chamaId, memberUserId) : row.standing();
        long[] d = row == null ? new long[]{0, 0} : dues(matrix.periods(), row);
        Map<UUID, PaymentTotal> yearToDate = contributions.paymentsByMember(chamaId, today.withDayOfYear(1), today);
        long mine = yearToDate.getOrDefault(memberUserId, PaymentTotal.NONE).totalMinor();
        Integer rank = mine <= 0 ? null
                : 1 + (int) yearToDate.entrySet().stream()
                .filter(e -> !e.getKey().equals(memberUserId) && e.getValue().totalMinor() > mine).count();
        return new MyContributionsSection(s.totalPaidMinor(), s.arrearsMinor(), s.advanceMinor(), s.latePeriods(),
                s.latestPeriod(), s.latestPeriodStatus(), mine, rate(d[1], d[0]), rank, matrix.members().size());
    }

    static MyLoansSection myLoans(List<LoanSnapshot> mine, int guaranteeRequests) {
        List<LoanSnapshot> active = mine.stream().filter(LoanSnapshot::active).toList();
        LoanSnapshot next = active.stream().filter(l -> l.nextDueDate() != null)
                .min(Comparator.comparing(LoanSnapshot::nextDueDate)).orElse(null);
        List<LoanSnapshot> disbursed = mine.stream().filter(l -> l.disbursedOn() != null).toList();
        List<MyLoan> list = mine.stream()
                .filter(l -> !"REJECTED".equals(l.status()) && !"CANCELLED".equals(l.status()))
                .map(l -> new MyLoan(l.id(), l.productName(), l.status(), l.purpose(), l.principalMinor(),
                        l.interestMinor(), l.totalDueMinor(), l.repaidMinor(), l.outstandingMinor(), l.arrearsMinor(),
                        l.daysInArrears(), l.totalDueMinor() == 0 ? 0 : Math.round(l.repaidMinor() * 10_000.0 / l.totalDueMinor()),
                        l.termMonths(), l.appliedOn(), l.disbursedOn(), l.maturityDate(), l.nextDueDate(), l.nextDueMinor()))
                .toList();
        return new MyLoansSection(active.size(), (int) mine.stream().filter(LoanSnapshot::inProgress).count(),
                active.stream().mapToLong(LoanSnapshot::outstandingMinor).sum(),
                active.stream().mapToLong(LoanSnapshot::arrearsMinor).sum(),
                next == null ? null : next.nextDueDate(), next == null ? 0 : next.nextDueMinor(), guaranteeRequests,
                disbursed.stream().mapToLong(LoanSnapshot::principalMinor).sum(),
                disbursed.stream().mapToLong(LoanSnapshot::repaidMinor).sum(), list);
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

    private static PaymentTotal sum(Map<UUID, PaymentTotal> totals) {
        return new PaymentTotal(totals.values().stream().mapToLong(PaymentTotal::totalMinor).sum(),
                totals.values().stream().mapToInt(PaymentTotal::count).sum());
    }

    private static LocalDate trendStart(LocalDate today, int months) {
        return YearMonth.from(today).minusMonths(months - 1L).atDay(1);
    }

    private static int clampLimit(int limit) {
        return Math.clamp(limit, 1, MAX_LIMIT);
    }

    private Map<UUID, MemberSummary> seatedById(UUID chamaId) {
        return members.seatedMembers(chamaId).stream().collect(Collectors.toMap(MemberSummary::userId, Function.identity()));
    }

    private static String name(MemberSummary member) {
        if (member == null) {
            return null;
        }
        String name = member.fullName().trim();
        return name.isEmpty() ? member.email() : name;
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
