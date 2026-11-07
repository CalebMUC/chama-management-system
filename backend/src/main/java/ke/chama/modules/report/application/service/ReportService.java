package ke.chama.modules.report.application.service;

import ke.chama.modules.account.api.MemberAccountSummary;
import ke.chama.modules.account.api.MemberAccounts;
import ke.chama.modules.account.api.SavingsProductSummary;
import ke.chama.modules.account.api.SavingsProducts;
import ke.chama.modules.auth.api.UserDirectory;
import ke.chama.modules.auth.api.UserSummary;
import ke.chama.modules.chama.api.ChamaDirectory;
import ke.chama.modules.chama.api.ChamaSummary;
import ke.chama.modules.contribution.api.ContributionReports;
import ke.chama.modules.contribution.api.ContributionReports.Cell;
import ke.chama.modules.contribution.api.ContributionReports.ContributionMatrix;
import ke.chama.modules.contribution.api.ContributionReports.PeriodColumn;
import ke.chama.modules.loan.api.LoanPortfolio;
import ke.chama.modules.loan.api.LoanPortfolio.LoanSnapshot;
import ke.chama.modules.member.api.MemberDirectory;
import ke.chama.modules.member.api.MemberSummary;
import ke.chama.modules.report.application.port.in.ReportUseCases;
import ke.chama.modules.report.application.port.in.ReportViews.AccountStatementView;
import ke.chama.modules.report.application.port.in.ReportViews.CashAccountView;
import ke.chama.modules.report.application.port.in.ReportViews.CashBookView;
import ke.chama.modules.report.application.port.in.ReportViews.CashLineView;
import ke.chama.modules.report.application.port.in.ReportViews.ContributionReportView;
import ke.chama.modules.report.application.port.in.ReportViews.FinancialPositionView;
import ke.chama.modules.report.application.port.in.ReportViews.IncomeStatementView;
import ke.chama.modules.report.application.port.in.ReportViews.LoanPortfolioView;
import ke.chama.modules.report.application.port.in.ReportViews.LoanRow;
import ke.chama.modules.report.application.port.in.ReportViews.MemberContributionRow;
import ke.chama.modules.report.application.port.in.ReportViews.MemberRef;
import ke.chama.modules.report.application.port.in.ReportViews.MemberStatementView;
import ke.chama.modules.report.application.port.in.ReportViews.ProductColumn;
import ke.chama.modules.report.application.port.in.ReportViews.SavingsRegisterView;
import ke.chama.modules.report.application.port.in.ReportViews.SavingsRow;
import ke.chama.modules.report.application.port.in.ReportViews.StatementLineView;
import ke.chama.modules.report.domain.exception.ReportExceptions.InvalidPeriodException;
import ke.chama.modules.report.domain.exception.ReportExceptions.MemberNotFoundException;
import ke.chama.modules.report.domain.exception.ReportExceptions.NotACashAccountException;
import ke.chama.modules.report.domain.model.FinancialStatements;
import ke.chama.modules.report.domain.model.FinancialStatements.AccountBalance;
import ke.chama.modules.report.domain.model.LoanAging;
import ke.chama.modules.transaction.api.Ledger;
import ke.chama.modules.transaction.api.Ledger.Statement;
import ke.chama.modules.transaction.api.LedgerAccountKey;
import ke.chama.modules.transaction.api.LedgerAccountSummary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Builds the reports from the ledger (the source of every balance) and the other modules' read
 * APIs. Nothing here writes business data; transactions are read-write only because contributions
 * and accounts bring themselves up to date (periods, dues, mandatory accounts) before answering.
 */
@Service
@Transactional
public class ReportService implements ReportUseCases {

    static final List<LedgerAccountKey> CASH_ACCOUNTS =
            List.of(LedgerAccountKey.CASH, LedgerAccountKey.BANK, LedgerAccountKey.MOBILE_MONEY);

    private final ChamaDirectory chamas;
    private final MemberDirectory members;
    private final UserDirectory users;
    private final Ledger ledger;
    private final SavingsProducts savingsProducts;
    private final MemberAccounts memberAccounts;
    private final ContributionReports contributions;
    private final LoanPortfolio loans;
    private final Clock clock;
    private final ZoneId zone;

    public ReportService(ChamaDirectory chamas, MemberDirectory members, UserDirectory users, Ledger ledger,
                         SavingsProducts savingsProducts, MemberAccounts memberAccounts,
                         ContributionReports contributions, LoanPortfolio loans, Clock clock,
                         @Value("${chama.time-zone:Africa/Nairobi}") String timeZone) {
        this.chamas = chamas;
        this.members = members;
        this.users = users;
        this.ledger = ledger;
        this.savingsProducts = savingsProducts;
        this.memberAccounts = memberAccounts;
        this.contributions = contributions;
        this.loans = loans;
        this.clock = clock;
        this.zone = ZoneId.of(timeZone);
    }

    // ---- financial statements --------------------------------------------------------------------

    @Override
    public FinancialPositionView financialPosition(UUID chamaId, LocalDate asOf) {
        LocalDate date = asOf == null ? today() : asOf;
        FinancialStatements.Position p = FinancialStatements.position(balances(chamaId, null, date));
        return new FinancialPositionView(chamaName(chamaId), date, currency(chamaId), p.assets(), p.liabilities(),
                p.equity(), p.liabilitiesAndEquityMinor(), p.balanced(), clock.instant());
    }

    @Override
    public IncomeStatementView incomeStatement(UUID chamaId, LocalDate from, LocalDate to) {
        LocalDate end = to == null ? today() : to;
        LocalDate start = from == null ? end.withDayOfYear(1) : from;
        requireOrder(start, end);
        FinancialStatements.Performance p = FinancialStatements.performance(balances(chamaId, start, end));
        return new IncomeStatementView(chamaName(chamaId), start, end, currency(chamaId), p.income(), p.expenses(),
                p.surplusMinor(), clock.instant());
    }

    private List<AccountBalance> balances(UUID chamaId, LocalDate from, LocalDate to) {
        return ledger.accountTotals(chamaId, from, to).stream()
                .map(t -> new AccountBalance(t.code(), t.name(), t.type(), t.balanceMinor()))
                .toList();
    }

    // ---- cash book --------------------------------------------------------------------------------

    @Override
    public CashBookView cashBook(UUID chamaId, String accountCode, LocalDate from, LocalDate to) {
        LocalDate end = to == null ? today() : to;
        LocalDate start = from == null ? end.withDayOfMonth(1) : from;
        requireOrder(start, end);
        Map<String, LedgerAccountSummary> cash = new LinkedHashMap<>();
        for (LedgerAccountKey key : CASH_ACCOUNTS) {
            ledger.account(chamaId, key).ifPresent(a -> cash.put(a.code(), a));
        }
        List<String> codes;
        if (accountCode == null || accountCode.isBlank()) {
            codes = List.copyOf(cash.keySet());
        } else {
            String code = accountCode.trim().toUpperCase(Locale.ROOT);
            if (!cash.containsKey(code)) {
                throw new NotACashAccountException(code);
            }
            codes = List.of(code);
        }
        List<CashAccountView> accounts = new ArrayList<>();
        for (String code : codes) {
            Statement s = ledger.accountStatement(chamaId, code, start, end);
            List<CashLineView> lines = s.lines().stream()
                    .map(l -> new CashLineView(l.entryDate(), l.entryNumber(), l.description(), l.reference(),
                            l.sourceType(), l.debitMinor(), l.creditMinor(), l.balanceMinor()))
                    .toList();
            accounts.add(new CashAccountView(s.accountCode(), s.accountName(), s.openingBalanceMinor(),
                    lines.stream().mapToLong(CashLineView::receiptMinor).sum(),
                    lines.stream().mapToLong(CashLineView::paymentMinor).sum(), s.closingBalanceMinor(), lines));
        }
        return new CashBookView(chamaName(chamaId), start, end, currency(chamaId), accounts,
                accounts.stream().mapToLong(CashAccountView::openingBalanceMinor).sum(),
                accounts.stream().mapToLong(CashAccountView::receiptsMinor).sum(),
                accounts.stream().mapToLong(CashAccountView::paymentsMinor).sum(),
                accounts.stream().mapToLong(CashAccountView::closingBalanceMinor).sum(), clock.instant());
    }

    // ---- contributions --------------------------------------------------------------------------

    @Override
    public ContributionReportView contributions(UUID chamaId, LocalDate from, LocalDate to) {
        if (from != null && to != null) {
            requireOrder(from, to);
        }
        ContributionMatrix matrix = contributions.matrix(chamaId);
        List<Integer> keep = IntStream.range(0, matrix.periods().size())
                .filter(i -> {
                    LocalDate due = matrix.periods().get(i).dueDate();
                    return (from == null || !due.isBefore(from)) && (to == null || !due.isAfter(to));
                })
                .boxed().toList();
        List<PeriodColumn> periods = keep.stream().map(matrix.periods()::get).toList();
        Map<UUID, MemberSummary> seated = seatedById(chamaId);
        List<MemberContributionRow> rows = matrix.members().stream().map(row -> {
            List<Cell> cells = keep.stream().map(row.cells()::get).toList();
            return new MemberContributionRow(ref(row.memberUserId(), seated, Map.of()), row.standing(), cells);
        }).toList();
        long expected = periods.stream().mapToLong(PeriodColumn::expectedMinor).sum();
        long collected = periods.stream().mapToLong(PeriodColumn::collectedMinor).sum();
        long arrears = periods.stream().filter(PeriodColumn::due)
                .mapToLong(p -> p.expectedMinor() - p.collectedMinor()).sum();
        return new ContributionReportView(chamaName(chamaId), matrix.currency(), periods, rows, expected, collected,
                arrears, clock.instant());
    }

    // ---- loans ----------------------------------------------------------------------------------

    @Override
    public LoanPortfolioView loanPortfolio(UUID chamaId) {
        List<LoanSnapshot> all = loans.loans(chamaId);
        List<LoanSnapshot> active = all.stream().filter(LoanSnapshot::active)
                .sorted(Comparator.comparingInt(LoanSnapshot::daysInArrears).reversed()
                        .thenComparing(Comparator.comparingLong(LoanSnapshot::outstandingMinor).reversed()))
                .toList();
        List<LoanSnapshot> pipeline = all.stream().filter(LoanSnapshot::inProgress).toList();
        LoanAging.Summary aging = LoanAging.of(active.stream()
                .map(l -> new LoanAging.LoanAmounts(l.outstandingMinor(), l.arrearsMinor(), l.daysInArrears()))
                .toList());

        Map<UUID, MemberSummary> seated = seatedById(chamaId);
        Map<UUID, UserSummary> others = others(all.stream().map(LoanSnapshot::borrowerUserId).toList(), seated);
        List<LoanRow> rows = new ArrayList<>();
        active.forEach(l -> rows.add(new LoanRow(ref(l.borrowerUserId(), seated, others), l,
                LoanAging.Bucket.of(l.daysInArrears()).name())));
        pipeline.forEach(l -> rows.add(new LoanRow(ref(l.borrowerUserId(), seated, others), l, null)));

        int awaitingApproval = (int) pipeline.stream()
                .filter(l -> "PENDING_GUARANTORS".equals(l.status()) || "PENDING_APPROVAL".equals(l.status())).count();
        int awaitingDisbursement = (int) pipeline.stream().filter(l -> "APPROVED".equals(l.status())).count();
        return new LoanPortfolioView(chamaName(chamaId), today(), currency(chamaId), aging.loans(),
                aging.outstandingMinor(), aging.arrearsMinor(), aging.loansInArrears(), aging.par30BasisPoints(),
                aging.buckets(), awaitingApproval, awaitingDisbursement,
                pipeline.stream().mapToLong(LoanSnapshot::principalMinor).sum(), rows, clock.instant());
    }

    // ---- savings ----------------------------------------------------------------------------------

    @Override
    public SavingsRegisterView savingsRegister(UUID chamaId) {
        List<SavingsProductSummary> products = savingsProducts.products(chamaId);
        Map<UUID, Map<UUID, Long>> balances = new LinkedHashMap<>();
        for (SavingsProductSummary p : products) {
            balances.put(p.id(), ledger.memberBalances(chamaId, p.ledgerAccountCode()));
        }
        List<SavingsProductSummary> shown = products.stream()
                .filter(p -> p.active() || balances.get(p.id()).values().stream().anyMatch(v -> v != 0))
                .toList();
        List<ProductColumn> columns = shown.stream().map(p -> new ProductColumn(p.id(), p.code(), p.name(), p.kind(),
                p.active(), balances.get(p.id()).values().stream().mapToLong(Long::longValue).sum())).toList();

        // Seated members by member number, then anyone else who still has a balance (e.g. a member who left).
        Map<UUID, MemberSummary> seated = seatedById(chamaId);
        Set<UUID> holders = new HashSet<>();
        balances.values().forEach(byMember -> byMember.forEach((member, balance) -> {
            if (balance != 0) {
                holders.add(member);
            }
        }));
        List<UUID> memberIds = new ArrayList<>(seated.keySet());
        holders.stream().filter(id -> !seated.containsKey(id)).sorted().forEach(memberIds::add);
        Map<UUID, UserSummary> others = others(memberIds, seated);

        List<SavingsRow> rows = memberIds.stream().map(id -> {
            List<Long> perProduct = shown.stream().map(p -> balances.get(p.id()).getOrDefault(id, 0L)).toList();
            return new SavingsRow(ref(id, seated, others), perProduct,
                    perProduct.stream().mapToLong(Long::longValue).sum());
        }).toList();
        return new SavingsRegisterView(chamaName(chamaId), currency(chamaId), columns, rows,
                columns.stream().mapToLong(ProductColumn::totalMinor).sum(), clock.instant());
    }

    // ---- member statement -------------------------------------------------------------------------

    @Override
    public MemberStatementView memberStatement(UUID chamaId, UUID memberUserId, LocalDate from, LocalDate to) {
        if (from != null && to != null) {
            requireOrder(from, to);
        }
        MemberSummary member = members.seatedMember(chamaId, memberUserId).orElseThrow(MemberNotFoundException::new);
        memberAccounts.openMandatoryAccounts(chamaId);
        Map<UUID, SavingsProductSummary> products = savingsProducts.products(chamaId).stream()
                .collect(Collectors.toMap(SavingsProductSummary::id, Function.identity()));

        List<AccountStatementView> savings = new ArrayList<>();
        for (MemberAccountSummary account : memberAccounts.accountsOf(chamaId, memberUserId)) {
            SavingsProductSummary product = products.get(account.productId());
            Statement s = ledger.memberStatement(chamaId, product.ledgerAccountCode(), memberUserId, from, to);
            savings.add(view(account.accountNumber(), product.code(), product.name(), s));
        }
        String penaltiesCode = ledger.account(chamaId, LedgerAccountKey.PENALTIES_RECEIVABLE)
                .map(LedgerAccountSummary::code).orElseThrow();
        Statement penalties = ledger.memberStatement(chamaId, penaltiesCode, memberUserId, from, to);
        List<LoanSnapshot> memberLoans = loans.loansOf(chamaId, memberUserId);

        long savingsTotal = savings.stream().mapToLong(AccountStatementView::closingBalanceMinor).sum();
        long loansOutstanding = memberLoans.stream().mapToLong(LoanSnapshot::outstandingMinor).sum();
        long penaltiesOutstanding = penalties.closingBalanceMinor();
        return new MemberStatementView(chamaName(chamaId), ref(memberUserId, Map.of(memberUserId, member), Map.of()),
                from, to, currency(chamaId), savingsTotal, loansOutstanding, penaltiesOutstanding,
                savingsTotal - loansOutstanding - penaltiesOutstanding, contributions.standing(chamaId, memberUserId),
                savings, memberLoans, view(null, penalties.accountCode(), penalties.accountName(), penalties),
                clock.instant());
    }

    private static AccountStatementView view(String accountNumber, String code, String name, Statement s) {
        return new AccountStatementView(accountNumber, code, name, s.openingBalanceMinor(), s.closingBalanceMinor(),
                s.lines().stream().map(l -> new StatementLineView(l.entryDate(), l.entryNumber(), l.description(),
                        l.reference(), l.sourceType(), l.debitMinor(), l.creditMinor(), l.balanceMinor())).toList());
    }

    // ---- helpers --------------------------------------------------------------------------------

    private static void requireOrder(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new InvalidPeriodException("'to' (" + to + ") must not be before 'from' (" + from + ")");
        }
    }

    private Map<UUID, MemberSummary> seatedById(UUID chamaId) {
        Map<UUID, MemberSummary> seated = new LinkedHashMap<>();
        members.seatedMembers(chamaId).forEach(m -> seated.put(m.userId(), m));
        return seated;
    }

    /** Names for people who are not seated members any more. */
    private Map<UUID, UserSummary> others(List<UUID> userIds, Map<UUID, MemberSummary> seated) {
        Set<UUID> missing = userIds.stream().filter(id -> !seated.containsKey(id)).collect(Collectors.toSet());
        return missing.isEmpty() ? new HashMap<>() : users.findByIds(missing);
    }

    static MemberRef ref(UUID userId, Map<UUID, MemberSummary> seated, Map<UUID, UserSummary> others) {
        MemberSummary m = seated.get(userId);
        if (m != null) {
            String name = m.fullName().trim();
            return new MemberRef(userId, name.isEmpty() ? m.email() : name, m.memberNumber());
        }
        UserSummary u = others.get(userId);
        return new MemberRef(userId, u == null ? null : u.fullName(), null);
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
