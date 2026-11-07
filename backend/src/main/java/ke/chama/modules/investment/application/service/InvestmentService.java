package ke.chama.modules.investment.application.service;

import ke.chama.modules.audit.api.AuditEntry;
import ke.chama.modules.audit.api.AuditTrail;
import ke.chama.modules.chama.api.ChamaDirectory;
import ke.chama.modules.chama.api.ChamaSummary;
import ke.chama.modules.expense.api.ExpenseTotals;
import ke.chama.modules.investment.application.port.in.InvestmentUseCases;
import ke.chama.modules.investment.application.port.out.InvestmentRepository;
import ke.chama.modules.investment.domain.exception.InvestmentExceptions.DuplicateReferenceException;
import ke.chama.modules.investment.domain.exception.InvestmentExceptions.InsufficientFundsException;
import ke.chama.modules.investment.domain.exception.InvestmentExceptions.InvalidInvestmentException;
import ke.chama.modules.investment.domain.exception.InvestmentExceptions.InvestmentNotFoundException;
import ke.chama.modules.investment.domain.model.Investment;
import ke.chama.modules.investment.domain.model.InvestmentTransaction;
import ke.chama.modules.member.api.MemberDirectory;
import ke.chama.modules.member.api.MemberSummary;
import ke.chama.modules.transaction.api.Ledger;
import ke.chama.modules.transaction.api.LedgerAccountKey;
import ke.chama.modules.transaction.api.PostingRequest;
import ke.chama.modules.transaction.api.PostingRequest.PostingLine;
import ke.chama.shared.domain.Money;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Investments with maker-checker approval, carried at cost in the ledger (1200 Investments).
 * <ul>
 *   <li>Funding and deposits: Dr Investments / Cr cash, bank or M-Pesa (which must hold enough).</li>
 *   <li>Income: Dr cash, bank or M-Pesa (or Investments, when reinvested) / Cr 4200 Investment income.</li>
 *   <li>Withdrawal: Dr cash, bank or M-Pesa / Cr Investments.</li>
 *   <li>Closure: Dr cash, bank or M-Pesa with the proceeds / Cr Investments with the book value; the
 *       difference is a gain (Cr Investment income) or a loss (Dr Investment income).</li>
 * </ul>
 * Valuations are recorded without posting anything: the books keep investments at cost.
 * <p>
 * Expenses can be charged to an investment (see the expense module), e.g. an Airbnb's rent and
 * bills; its profit is then its income less those expenses, plus its gain in value.
 */
@Service
@Transactional
public class InvestmentService implements InvestmentUseCases {

    static final String AUDIT_TARGET = "INVESTMENT";

    private final InvestmentRepository repository;
    private final MemberDirectory members;
    private final ChamaDirectory chamas;
    private final Ledger ledger;
    private final AuditTrail auditTrail;
    private final ExpenseTotals expenses;
    private final Clock clock;
    private final ZoneId zone;

    public InvestmentService(InvestmentRepository repository, MemberDirectory members, ChamaDirectory chamas,
                             Ledger ledger, AuditTrail auditTrail, ExpenseTotals expenses, Clock clock,
                             @Value("${chama.time-zone:Africa/Nairobi}") String timeZone) {
        this.repository = repository;
        this.members = members;
        this.chamas = chamas;
        this.ledger = ledger;
        this.auditTrail = auditTrail;
        this.expenses = expenses;
        this.clock = clock;
        this.zone = ZoneId.of(timeZone);
    }

    // ---- proposal and decisions -----------------------------------------------------------------

    @Override
    public InvestmentView propose(UUID chamaId, UUID actorUserId, Proposal p) {
        Investment investment = Investment.propose(chamaId, actorUserId, p.name(), p.type(), p.institution(),
                p.description(), p.amountMinor(), currency(chamaId), p.expectedReturnBasisPoints(), p.maturityDate(),
                chamas.approvalQuorum(chamaId).orElseThrow(), today(), clock.instant());
        repository.insert(investment);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("name", investment.name());
        details.put("type", investment.type().name());
        details.put("amountMinor", investment.amountMinor());
        if (investment.institution() != null) {
            details.put("institution", investment.institution());
        }
        audit(investment, actorUserId, "INVESTMENT_PROPOSED", details);
        return view(investment);
    }

    @Override
    public InvestmentView approve(UUID chamaId, UUID actorUserId, UUID investmentId, String note) {
        Investment investment = load(chamaId, investmentId);
        investment.approve(actorUserId, note, clock.instant());
        save(investment);
        audit(investment, actorUserId, "INVESTMENT_APPROVAL_GIVEN", Map.of("approvals", investment.approvals().size(),
                "requiredApprovals", investment.requiredApprovals()));
        if (investment.status() == Investment.Status.APPROVED) {
            audit(investment, actorUserId, "INVESTMENT_APPROVED", Map.of("amountMinor", investment.amountMinor()));
        }
        return view(investment);
    }

    @Override
    public InvestmentView reject(UUID chamaId, UUID actorUserId, UUID investmentId, String reason) {
        Investment investment = load(chamaId, investmentId);
        investment.reject(actorUserId, reason, clock.instant());
        save(investment);
        audit(investment, actorUserId, "INVESTMENT_REJECTED", Map.of("reason", investment.rejectionReason()));
        return view(investment);
    }

    @Override
    public InvestmentView cancel(UUID chamaId, UUID actorUserId, UUID investmentId) {
        Investment investment = load(chamaId, investmentId);
        investment.cancel(actorUserId, clock.instant());
        save(investment);
        audit(investment, actorUserId, "INVESTMENT_CANCELLED", Map.of());
        return view(investment);
    }

    // ---- money ----------------------------------------------------------------------------------

    @Override
    public InvestmentDetailView fund(UUID chamaId, UUID actorUserId, UUID investmentId, Movement m) {
        Investment investment = load(chamaId, investmentId);
        InvestmentTransaction funding = investment.fund(actorUserId, m.method(), m.reference(), m.date(), today(),
                clock.instant());
        requireUnusedReference(chamaId, funding);
        LedgerAccountKey source = accountFor(funding.method());
        Money amount = money(funding.amountMinor(), investment.currency());
        requireFunds(chamaId, source, amount, investment.currency());
        post(investment, funding, "Investment funded: " + investment.name(), List.of(
                PostingLine.debit(LedgerAccountKey.INVESTMENTS, amount),
                PostingLine.credit(source, amount)));
        audit(investment, actorUserId, "INVESTMENT_FUNDED", Map.of("amountMinor", funding.amountMinor(),
                "method", funding.method()));
        return detail(investment);
    }

    @Override
    public InvestmentDetailView deposit(UUID chamaId, UUID actorUserId, UUID investmentId, Movement m) {
        Investment investment = load(chamaId, investmentId);
        InvestmentTransaction deposit = investment.deposit(actorUserId, amount(m), m.method(), m.reference(), m.date(),
                m.note(), today(), clock.instant());
        requireUnusedReference(chamaId, deposit);
        LedgerAccountKey source = accountFor(deposit.method());
        Money amount = money(deposit.amountMinor(), investment.currency());
        requireFunds(chamaId, source, amount, investment.currency());
        post(investment, deposit, "Deposit to investment: " + investment.name(), List.of(
                PostingLine.debit(LedgerAccountKey.INVESTMENTS, amount),
                PostingLine.credit(source, amount)));
        audit(investment, actorUserId, "INVESTMENT_DEPOSIT", Map.of("amountMinor", deposit.amountMinor(),
                "method", deposit.method(), "bookValueMinor", investment.bookValueMinor()));
        return detail(investment);
    }

    @Override
    public InvestmentDetailView recordIncome(UUID chamaId, UUID actorUserId, UUID investmentId, Movement m) {
        Investment investment = load(chamaId, investmentId);
        InvestmentTransaction income = investment.recordIncome(actorUserId, amount(m), m.method(), m.reference(),
                m.date(), m.note(), today(), clock.instant());
        requireUnusedReference(chamaId, income);
        Money amount = money(income.amountMinor(), investment.currency());
        post(investment, income, "Investment income: " + investment.name(), List.of(
                PostingLine.debit(income.reinvested() ? LedgerAccountKey.INVESTMENTS : accountFor(income.method()), amount),
                PostingLine.credit(LedgerAccountKey.INVESTMENT_INCOME, amount)));
        audit(investment, actorUserId, "INVESTMENT_INCOME_RECORDED", Map.of("amountMinor", income.amountMinor(),
                "method", income.method()));
        return detail(investment);
    }

    @Override
    public InvestmentDetailView withdraw(UUID chamaId, UUID actorUserId, UUID investmentId, Movement m) {
        Investment investment = load(chamaId, investmentId);
        InvestmentTransaction withdrawal = investment.withdraw(actorUserId, amount(m), m.method(), m.reference(),
                m.date(), m.note(), today(), clock.instant());
        requireUnusedReference(chamaId, withdrawal);
        Money amount = money(withdrawal.amountMinor(), investment.currency());
        post(investment, withdrawal, "Investment withdrawal: " + investment.name(), List.of(
                PostingLine.debit(accountFor(withdrawal.method()), amount),
                PostingLine.credit(LedgerAccountKey.INVESTMENTS, amount)));
        audit(investment, actorUserId, "INVESTMENT_WITHDRAWAL", Map.of("amountMinor", withdrawal.amountMinor(),
                "bookValueMinor", investment.bookValueMinor()));
        return detail(investment);
    }

    @Override
    public InvestmentDetailView close(UUID chamaId, UUID actorUserId, UUID investmentId, Movement m) {
        Investment investment = load(chamaId, investmentId);
        long bookValue = investment.bookValueMinor();
        InvestmentTransaction closure = investment.close(actorUserId, amount(m), m.method(), m.reference(), m.date(),
                m.note(), today(), clock.instant());
        requireUnusedReference(chamaId, closure);
        String currency = investment.currency();
        long gain = investment.realisedGainMinor();
        List<PostingLine> lines = new ArrayList<>();
        if (closure.amountMinor() > 0) {
            lines.add(PostingLine.debit(accountFor(closure.method()), money(closure.amountMinor(), currency)));
        }
        if (gain < 0) {
            lines.add(PostingLine.debit(LedgerAccountKey.INVESTMENT_INCOME, money(-gain, currency))
                    .withMemo("Loss on closing the investment"));
        }
        lines.add(PostingLine.credit(LedgerAccountKey.INVESTMENTS, money(bookValue, currency)));
        if (gain > 0) {
            lines.add(PostingLine.credit(LedgerAccountKey.INVESTMENT_INCOME, money(gain, currency))
                    .withMemo("Gain on closing the investment"));
        }
        post(investment, closure, "Investment closed: " + investment.name(), lines);
        audit(investment, actorUserId, "INVESTMENT_CLOSED", Map.of("proceedsMinor", closure.amountMinor(),
                "bookValueMinor", bookValue, "realisedGainMinor", gain));
        return detail(investment);
    }

    @Override
    public InvestmentDetailView value(UUID chamaId, UUID actorUserId, UUID investmentId, long valueMinor,
                                      LocalDate valuedOn, String note) {
        Investment investment = load(chamaId, investmentId);
        Investment.Valuation valuation = investment.value(actorUserId, valueMinor, valuedOn, note, today(), clock.instant());
        repository.insertValuation(valuation);
        save(investment);
        audit(investment, actorUserId, "INVESTMENT_VALUED", Map.of("valueMinor", valueMinor,
                "valuedOn", valuation.valuedOn().toString(), "bookValueMinor", investment.bookValueMinor()));
        return detail(investment);
    }

    // ---- queries --------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public InvestmentDetailView investment(UUID chamaId, UUID investmentId) {
        return detail(load(chamaId, investmentId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<InvestmentView> investments(UUID chamaId, Investment.Status status, Investment.Type type, Pageable pageable) {
        Map<UUID, MemberSummary> seated = seatedById(chamaId);
        Map<UUID, Long> spent = expenses.paidByInvestment(chamaId);
        LocalDate today = today();
        return repository.investments(chamaId, status, type, pageable).map(i -> view(i, seated, spent, today));
    }

    @Override
    @Transactional(readOnly = true)
    public PortfolioView portfolio(UUID chamaId) {
        List<Investment> funded = repository.fundedInvestments(chamaId);
        List<Investment> active = funded.stream().filter(i -> i.status() == Investment.Status.ACTIVE).toList();
        Map<Investment.Type, TypeTotal> byType = new EnumMap<>(Investment.Type.class);
        for (Investment i : active) {
            byType.merge(i.type(), new TypeTotal(i.type(), 1, i.bookValueMinor(), i.marketValueMinor()),
                    (a, b) -> new TypeTotal(a.type(), a.count() + b.count(), a.bookValueMinor() + b.bookValueMinor(),
                            a.marketValueMinor() + b.marketValueMinor()));
        }
        LocalDate today = today();
        Map<UUID, MemberSummary> seated = seatedById(chamaId);
        Map<UUID, Long> spent = expenses.paidByInvestment(chamaId);
        List<InvestmentView> maturing = active.stream()
                .filter(i -> i.maturityDate() != null && !i.maturityDate().isAfter(today.plusDays(30)))
                .sorted(Comparator.comparing(Investment::maturityDate))
                .map(i -> view(i, seated, spent, today))
                .toList();
        long invested = funded.stream().mapToLong(Investment::investedMinor).sum();
        long income = funded.stream().mapToLong(Investment::incomeMinor).sum();
        long expensesMinor = funded.stream().mapToLong(i -> spent.getOrDefault(i.id(), 0L)).sum();
        long realised = funded.stream().filter(i -> i.realisedGainMinor() != null).mapToLong(Investment::realisedGainMinor).sum();
        long unrealised = active.stream().filter(i -> i.unrealisedGainMinor() != null)
                .mapToLong(Investment::unrealisedGainMinor).sum();
        long totalReturn = income - expensesMinor + realised + unrealised;
        int returnBp = invested == 0 ? 0 : BigDecimal.valueOf(totalReturn).multiply(BigDecimal.valueOf(10_000))
                .divide(BigDecimal.valueOf(invested), 0, RoundingMode.HALF_UP).intValueExact();
        return new PortfolioView(currency(chamaId), active.size(),
                active.stream().mapToLong(Investment::bookValueMinor).sum(),
                active.stream().mapToLong(Investment::marketValueMinor).sum(),
                invested, income, expensesMinor, realised, unrealised, totalReturn, returnBp,
                byType.values().stream().sorted(Comparator.comparingLong(TypeTotal::bookValueMinor).reversed()).toList(),
                maturing);
    }

    // ---- helpers --------------------------------------------------------------------------------

    private void post(Investment investment, InvestmentTransaction transaction, String description, List<PostingLine> lines) {
        UUID entryId = ledger.post(new PostingRequest(investment.chamaId(), transaction.date(), description,
                transaction.reference(), "INVESTMENT_" + transaction.kind().name(), transaction.id().toString(),
                transaction.recordedBy(), lines));
        repository.insertTransaction(transaction.postedAs(entryId));
        save(investment);
    }

    private void requireFunds(UUID chamaId, LedgerAccountKey source, Money amount, String currency) {
        Money available = ledger.balance(chamaId, source);
        if (available.isLessThan(amount)) {
            throw new InsufficientFundsException(accountName(source), formatted(available.minorUnits(), currency));
        }
    }

    private void requireUnusedReference(UUID chamaId, InvestmentTransaction transaction) {
        if (transaction.reference() != null
                && repository.referenceExists(chamaId, transaction.method(), transaction.reference())) {
            throw new DuplicateReferenceException(transaction.reference());
        }
    }

    private static long amount(Movement m) {
        if (m.amountMinor() == null) {
            throw new InvalidInvestmentException("amountMinor is required");
        }
        return m.amountMinor();
    }

    private void audit(Investment investment, UUID actorUserId, String action, Map<String, ?> details) {
        auditTrail.record(AuditEntry.of(investment.chamaId(), actorUserId, action, AUDIT_TARGET, investment.id())
                .with(details));
    }

    private void save(Investment investment) {
        if (!repository.update(investment)) {
            throw new OptimisticLockingFailureException("Investment " + investment.id() + " changed concurrently");
        }
    }

    private Investment load(UUID chamaId, UUID investmentId) {
        return repository.investment(chamaId, investmentId).orElseThrow(InvestmentNotFoundException::new);
    }

    private Map<UUID, MemberSummary> seatedById(UUID chamaId) {
        return members.seatedMembers(chamaId).stream().collect(Collectors.toMap(MemberSummary::userId, Function.identity()));
    }

    private String currency(UUID chamaId) {
        return chamas.find(chamaId).map(ChamaSummary::currency).orElseThrow();
    }

    private static LedgerAccountKey accountFor(String method) {
        return switch (method) {
            case "BANK" -> LedgerAccountKey.BANK;
            case "MPESA" -> LedgerAccountKey.MOBILE_MONEY;
            default -> LedgerAccountKey.CASH;
        };
    }

    private static String accountName(LedgerAccountKey key) {
        return switch (key) {
            case BANK -> "bank account";
            case MOBILE_MONEY -> "M-Pesa account";
            default -> "cash on hand";
        };
    }

    private static Money money(long minor, String currency) {
        return Money.of(minor, Currency.getInstance(currency));
    }

    private static String formatted(long minor, String currency) {
        int digits = Math.max(Currency.getInstance(currency).getDefaultFractionDigits(), 0);
        return currency + " " + new DecimalFormat(digits == 0 ? "#,##0" : "#,##0." + "0".repeat(digits))
                .format(BigDecimal.valueOf(minor, digits));
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(zone));
    }

    // ---- views ----------------------------------------------------------------------------------

    private InvestmentView view(Investment investment) {
        return view(investment, seatedById(investment.chamaId()), expenses.paidByInvestment(investment.chamaId()), today());
    }

    private InvestmentDetailView detail(Investment investment) {
        List<TransactionView> transactions = repository.transactions(investment.chamaId(), investment.id()).stream()
                .map(t -> new TransactionView(t.id(), t.kind(), t.amountMinor(), t.method(), t.reference(), t.date(),
                        t.note(), t.ledgerEntryId(), t.recordedBy(), t.recordedAt()))
                .toList();
        List<ValuationView> valuations = repository.valuations(investment.chamaId(), investment.id()).stream()
                .map(v -> new ValuationView(v.id(), v.valueMinor(), v.valuedOn(), v.note(), v.recordedBy(), v.recordedAt()))
                .toList();
        return new InvestmentDetailView(view(investment), transactions, valuations);
    }

    private static InvestmentView view(Investment i, Map<UUID, MemberSummary> seated, Map<UUID, Long> spent,
                                       LocalDate today) {
        List<ApprovalView> approvals = i.approvals().stream()
                .map(a -> new ApprovalView(a.approverUserId(), name(seated.get(a.approverUserId())), a.note(), a.approvedAt()))
                .toList();
        boolean funded = i.fundedOn() != null;
        return new InvestmentView(i.id(), i.name(), i.type(), i.institution(), i.description(), i.amountMinor(),
                i.currency(), i.expectedReturnBasisPoints(), i.maturityDate(), i.status(), i.proposedBy(),
                name(seated.get(i.proposedBy())), i.requiredApprovals(), approvals, i.rejectedBy(), i.rejectedAt(),
                i.rejectionReason(), i.cancelledAt(), i.fundedOn(), i.bookValueMinor(), i.incomeMinor(),
                i.withdrawnMinor(), i.depositedMinor(),
                funded && i.status() == Investment.Status.ACTIVE ? i.marketValueMinor() : 0,
                i.latestValuationMinor(), i.latestValuationOn(), i.unrealisedGainMinor(), i.closedOn(),
                i.closingProceedsMinor(), i.realisedGainMinor(), i.performance(spent.getOrDefault(i.id(), 0L), today));
    }

    private static String name(MemberSummary member) {
        return member == null ? null : member.fullName().trim();
    }
}
