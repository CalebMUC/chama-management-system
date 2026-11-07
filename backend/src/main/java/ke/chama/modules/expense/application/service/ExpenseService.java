package ke.chama.modules.expense.application.service;

import ke.chama.modules.audit.api.AuditEntry;
import ke.chama.modules.audit.api.AuditTrail;
import ke.chama.modules.chama.api.ChamaDirectory;
import ke.chama.modules.chama.api.ChamaSummary;
import ke.chama.modules.expense.api.ExpenseTotals;
import ke.chama.modules.expense.api.InvestmentLookup;
import ke.chama.modules.expense.api.InvestmentLookup.LinkedInvestment;
import ke.chama.modules.expense.application.port.in.ExpenseUseCases;
import ke.chama.modules.expense.application.port.out.ExpenseRepository;
import ke.chama.modules.expense.domain.exception.ExpenseExceptions.DuplicateReferenceException;
import ke.chama.modules.expense.domain.exception.ExpenseExceptions.ExpenseNotFoundException;
import ke.chama.modules.expense.domain.exception.ExpenseExceptions.InsufficientFundsException;
import ke.chama.modules.expense.domain.exception.ExpenseExceptions.InvalidExpenseAccountException;
import ke.chama.modules.expense.domain.exception.ExpenseExceptions.InvalidExpenseException;
import ke.chama.modules.expense.domain.exception.ExpenseExceptions.InvalidExpenseInvestmentException;
import ke.chama.modules.expense.domain.model.Expense;
import ke.chama.modules.member.api.MemberDirectory;
import ke.chama.modules.member.api.MemberSummary;
import ke.chama.modules.transaction.api.EntrySide;
import ke.chama.modules.transaction.api.Ledger;
import ke.chama.modules.transaction.api.LedgerAccountKey;
import ke.chama.modules.transaction.api.LedgerAccountSummary;
import ke.chama.modules.transaction.api.PostingRequest;
import ke.chama.modules.transaction.api.PostingRequest.PostingLine;
import ke.chama.shared.domain.Money;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Expenses with maker-checker approval. Paying debits the expense account and credits the
 * account the money left (cash, bank or M-Pesa), which must hold enough.
 */
@Service
@Transactional
public class ExpenseService implements ExpenseUseCases, ExpenseTotals {

    static final String DEFAULT_ACCOUNT = "5000";
    static final String SOURCE = "EXPENSE";
    static final String AUDIT_TARGET = "EXPENSE";

    private final ExpenseRepository repository;
    private final MemberDirectory members;
    private final ChamaDirectory chamas;
    private final Ledger ledger;
    private final AuditTrail auditTrail;
    private final InvestmentLookup investments;
    private final Clock clock;
    private final ZoneId zone;

    public ExpenseService(ExpenseRepository repository, MemberDirectory members, ChamaDirectory chamas, Ledger ledger,
                          AuditTrail auditTrail, InvestmentLookup investments, Clock clock,
                          @Value("${chama.time-zone:Africa/Nairobi}") String timeZone) {
        this.repository = repository;
        this.members = members;
        this.chamas = chamas;
        this.ledger = ledger;
        this.auditTrail = auditTrail;
        this.investments = investments;
        this.clock = clock;
        this.zone = ZoneId.of(timeZone);
    }

    // ---- commands -------------------------------------------------------------------------------

    @Override
    public ExpenseView request(UUID chamaId, UUID actorUserId, NewExpense request) {
        String code = request.accountCode() == null || request.accountCode().isBlank() ? DEFAULT_ACCOUNT
                : request.accountCode().trim();
        LedgerAccountSummary account = ledger.account(chamaId, code)
                .orElseThrow(() -> new InvalidExpenseAccountException("There is no account " + code + " in the chart of accounts"));
        if (!account.isExpense()) {
            throw new InvalidExpenseAccountException("Account " + account.code() + " (" + account.name()
                    + ") is not an expense account");
        }
        if (!account.active()) {
            throw new InvalidExpenseAccountException("Account " + account.code() + " (" + account.name() + ") is inactive");
        }
        if (request.investmentId() != null) {
            LinkedInvestment investment = investments.find(chamaId, request.investmentId())
                    .orElseThrow(() -> new InvalidExpenseInvestmentException("There is no such investment in this chama"));
            if (!investment.active()) {
                throw new InvalidExpenseInvestmentException("Expenses can only be charged to an active investment; "
                        + investment.name() + " is not active");
            }
        }
        Expense expense = Expense.request(chamaId, actorUserId, request.amountMinor(), currency(chamaId),
                request.payee(), request.description(), account.code(), request.investmentId(), request.incurredOn(),
                chamas.approvalQuorum(chamaId).orElseThrow(), today(), clock.instant());
        repository.insert(expense);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("amountMinor", expense.amountMinor());
        details.put("payee", expense.payee());
        details.put("description", expense.description());
        details.put("accountCode", expense.accountCode());
        if (expense.investmentId() != null) {
            details.put("investmentId", expense.investmentId().toString());
        }
        audit(expense, actorUserId, "EXPENSE_REQUESTED", details);
        return view(expense);
    }

    @Override
    public ExpenseView approve(UUID chamaId, UUID actorUserId, UUID expenseId, String note) {
        Expense expense = load(chamaId, expenseId);
        expense.approve(actorUserId, note, clock.instant());
        save(expense);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("approvals", expense.approvals().size());
        details.put("requiredApprovals", expense.requiredApprovals());
        if (note != null && !note.isBlank()) {
            details.put("note", note.trim());
        }
        audit(expense, actorUserId, "EXPENSE_APPROVAL_GIVEN", details);
        if (expense.status() == Expense.Status.APPROVED) {
            audit(expense, actorUserId, "EXPENSE_APPROVED", Map.of("amountMinor", expense.amountMinor()));
        }
        return view(expense);
    }

    @Override
    public ExpenseView reject(UUID chamaId, UUID actorUserId, UUID expenseId, String reason) {
        Expense expense = load(chamaId, expenseId);
        expense.reject(actorUserId, reason, clock.instant());
        save(expense);
        audit(expense, actorUserId, "EXPENSE_REJECTED", Map.of("reason", expense.rejectionReason()));
        return view(expense);
    }

    @Override
    public ExpenseView cancel(UUID chamaId, UUID actorUserId, UUID expenseId) {
        Expense expense = load(chamaId, expenseId);
        expense.cancel(actorUserId, clock.instant());
        save(expense);
        audit(expense, actorUserId, "EXPENSE_CANCELLED", Map.of());
        return view(expense);
    }

    @Override
    public ExpenseView pay(UUID chamaId, UUID actorUserId, UUID expenseId, Payment payment) {
        Expense expense = load(chamaId, expenseId);
        expense.pay(actorUserId, payment.method(), payment.reference(), payment.paidOn(), today());
        if (expense.paymentReference() != null
                && repository.referenceExists(chamaId, expense.paymentMethod(), expense.paymentReference())) {
            throw new DuplicateReferenceException(expense.paymentReference());
        }
        LedgerAccountKey source = accountFor(expense.paymentMethod());
        Money amount = Money.of(expense.amountMinor(), Currency.getInstance(expense.currency()));
        Money available = ledger.balance(chamaId, source);
        if (available.isLessThan(amount)) {
            throw new InsufficientFundsException(accountName(source), formatted(available.minorUnits(), expense.currency()));
        }
        UUID entryId = ledger.post(new PostingRequest(chamaId, expense.paidOn(),
                "Expense: " + expense.description() + " (" + expense.payee() + ")", expense.paymentReference(), SOURCE,
                expense.id().toString(), actorUserId, List.of(
                new PostingLine(null, expense.accountCode(), EntrySide.DEBIT, amount, null, null),
                PostingLine.credit(source, amount))));
        expense.paymentPostedAs(entryId);
        save(expense);
        audit(expense, actorUserId, "EXPENSE_PAID", Map.of("amountMinor", expense.amountMinor(),
                "method", expense.paymentMethod(), "payee", expense.payee()));
        return view(expense);
    }

    // ---- queries --------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public ExpenseView expense(UUID chamaId, UUID expenseId) {
        return view(load(chamaId, expenseId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ExpenseView> expenses(UUID chamaId, Expense.Status status, UUID investmentId, LocalDate from,
                                      LocalDate to, Pageable pageable) {
        requireRange(from, to);
        Map<UUID, MemberSummary> seated = seatedById(chamaId);
        Map<String, String> names = new HashMap<>();
        return repository.expenses(chamaId, status, investmentId, from, to, pageable).map(e -> view(e, seated, names));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Long> paidByInvestment(UUID chamaId) {
        return repository.paidByInvestment(chamaId);
    }

    @Override
    @Transactional(readOnly = true)
    public ExpenseQueue queue(UUID chamaId) {
        PageRequest one = PageRequest.of(0, 1);
        return new ExpenseQueue(
                repository.expenses(chamaId, Expense.Status.PENDING_APPROVAL, null, null, null, one).getTotalElements(),
                repository.expenses(chamaId, Expense.Status.APPROVED, null, null, null, one).getTotalElements(),
                repository.awaitingPaymentMinor(chamaId));
    }

    @Override
    @Transactional(readOnly = true)
    public SummaryView summary(UUID chamaId, LocalDate from, LocalDate to) {
        LocalDate end = to == null ? today() : to;
        LocalDate start = from == null ? end.withDayOfMonth(1) : from;
        requireRange(start, end);
        Map<String, String> accountNames = new HashMap<>();
        List<AccountTotal> byAccount = repository.paidByAccount(chamaId, start, end).stream()
                .map(t -> new AccountTotal(t.accountCode(), accountName(chamaId, t.accountCode(), accountNames),
                        t.totalMinor(), t.count()))
                .toList();
        return new SummaryView(currency(chamaId), start, end,
                byAccount.stream().mapToLong(AccountTotal::totalMinor).sum(),
                byAccount.stream().mapToInt(AccountTotal::count).sum(),
                repository.awaitingPaymentMinor(chamaId), byAccount);
    }

    // ---- helpers --------------------------------------------------------------------------------

    private static void requireRange(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidExpenseException("from must not be after to");
        }
    }

    private void audit(Expense expense, UUID actorUserId, String action, Map<String, ?> details) {
        auditTrail.record(AuditEntry.of(expense.chamaId(), actorUserId, action, AUDIT_TARGET, expense.id()).with(details));
    }

    private void save(Expense expense) {
        if (!repository.update(expense)) {
            throw new OptimisticLockingFailureException("Expense " + expense.id() + " changed concurrently");
        }
    }

    private Expense load(UUID chamaId, UUID expenseId) {
        return repository.expense(chamaId, expenseId).orElseThrow(ExpenseNotFoundException::new);
    }

    private Map<UUID, MemberSummary> seatedById(UUID chamaId) {
        return members.seatedMembers(chamaId).stream().collect(Collectors.toMap(MemberSummary::userId, Function.identity()));
    }

    private String accountName(UUID chamaId, String code, Map<String, String> cache) {
        return cache.computeIfAbsent(code, c -> ledger.account(chamaId, c).map(LedgerAccountSummary::name).orElse(null));
    }

    /** Cached alongside account names, keyed by the investment id. */
    private String investmentName(UUID chamaId, UUID investmentId, Map<String, String> cache) {
        if (investmentId == null) {
            return null;
        }
        return cache.computeIfAbsent(investmentId.toString(),
                k -> investments.find(chamaId, investmentId).map(LinkedInvestment::name).orElse(null));
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

    private static String formatted(long minor, String currency) {
        int digits = Math.max(Currency.getInstance(currency).getDefaultFractionDigits(), 0);
        return currency + " " + new DecimalFormat(digits == 0 ? "#,##0" : "#,##0." + "0".repeat(digits))
                .format(BigDecimal.valueOf(minor, digits));
    }

    private String currency(UUID chamaId) {
        return chamas.find(chamaId).map(ChamaSummary::currency).orElseThrow();
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(zone));
    }

    private ExpenseView view(Expense expense) {
        return view(expense, seatedById(expense.chamaId()), new HashMap<>());
    }

    private ExpenseView view(Expense e, Map<UUID, MemberSummary> seated, Map<String, String> accountNames) {
        List<ApprovalView> approvals = e.approvals().stream()
                .map(a -> new ApprovalView(a.approverUserId(), name(seated.get(a.approverUserId())), a.note(), a.approvedAt()))
                .toList();
        return new ExpenseView(e.id(), e.amountMinor(), e.currency(), e.payee(), e.description(), e.accountCode(),
                accountName(e.chamaId(), e.accountCode(), accountNames), e.investmentId(),
                investmentName(e.chamaId(), e.investmentId(), accountNames), e.incurredOn(), e.status(), e.requestedBy(),
                name(seated.get(e.requestedBy())), e.requiredApprovals(), approvals, e.rejectedBy(), e.rejectedAt(),
                e.rejectionReason(), e.cancelledAt(), e.paidOn(), e.paidBy(), e.paymentMethod(), e.paymentReference(),
                e.paymentEntryId());
    }

    private static String name(MemberSummary member) {
        return Optional.ofNullable(member).map(m -> m.fullName().trim()).orElse(null);
    }
}
