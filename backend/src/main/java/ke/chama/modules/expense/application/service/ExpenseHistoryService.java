package ke.chama.modules.expense.application.service;

import ke.chama.modules.audit.api.AuditEntry;
import ke.chama.modules.audit.api.AuditTrail;
import ke.chama.modules.chama.api.ChamaDirectory;
import ke.chama.modules.chama.api.ChamaSummary;
import ke.chama.modules.expense.api.ExpenseHistory;
import ke.chama.modules.expense.application.port.out.ExpenseRepository;
import ke.chama.modules.expense.domain.model.Expense;
import ke.chama.modules.transaction.api.EntrySide;
import ke.chama.modules.transaction.api.Ledger;
import ke.chama.modules.transaction.api.LedgerAccountKey;
import ke.chama.modules.transaction.api.PostingRequest;
import ke.chama.modules.transaction.api.PostingRequest.PostingLine;
import ke.chama.shared.domain.Money;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Expenses brought over from a chama's spreadsheets, posted as paying an expense by hand would. */
@Service
class ExpenseHistoryService implements ExpenseHistory {

    static final String UNDO_REASON = "Historical import undone";

    private final ExpenseRepository repository;
    private final ChamaDirectory chamas;
    private final Ledger ledger;
    private final AuditTrail auditTrail;
    private final Clock clock;
    private final ZoneId zone;

    ExpenseHistoryService(ExpenseRepository repository, ChamaDirectory chamas, Ledger ledger, AuditTrail auditTrail,
                          Clock clock, @Value("${chama.time-zone:Africa/Nairobi}") String timeZone) {
        this.repository = repository;
        this.chamas = chamas;
        this.ledger = ledger;
        this.auditTrail = auditTrail;
        this.clock = clock;
        this.zone = ZoneId.of(timeZone);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> usedReferences(UUID chamaId) {
        return repository.usedReferences(chamaId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int importExpenses(UUID chamaId, UUID actorUserId, UUID batchId, List<HistoricalExpense> expenses) {
        String currency = chamas.find(chamaId).map(ChamaSummary::currency).orElse("KES");
        LocalDate today = LocalDate.now(clock.withZone(zone));
        Instant now = clock.instant();
        List<UUID> ids = new ArrayList<>();
        long total = 0;
        for (HistoricalExpense h : expenses) {
            Expense expense = Expense.historical(chamaId, actorUserId, h.amountMinor(), currency, h.payee(),
                    h.description(), h.accountCode(), h.investmentId(), h.paidOn(), today, now);
            repository.insert(expense);
            expense.pay(actorUserId, h.method(), h.reference(), h.paidOn(), today);
            Money amount = Money.of(expense.amountMinor(), Currency.getInstance(currency));
            expense.paymentPostedAs(ledger.post(new PostingRequest(chamaId, expense.paidOn(),
                    "Expense: " + expense.description() + " (" + expense.payee() + ")", expense.paymentReference(),
                    ExpenseService.SOURCE, expense.id().toString(), actorUserId, List.of(
                    new PostingLine(null, expense.accountCode(), EntrySide.DEBIT, amount, null, null),
                    PostingLine.credit(accountFor(expense.paymentMethod()), amount)))));
            if (!repository.update(expense)) {
                throw new OptimisticLockingFailureException("Expense " + expense.id() + " changed concurrently");
            }
            ids.add(expense.id());
            total += expense.amountMinor();
        }
        repository.markImported(chamaId, ids, batchId);
        auditTrail.record(AuditEntry.of(chamaId, actorUserId, "EXPENSES_IMPORTED", "IMPORT", batchId)
                .with(Map.of("expenses", ids.size(), "totalMinor", total)));
        return ids.size();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int undoExpenses(UUID chamaId, UUID actorUserId, UUID batchId) {
        List<Expense> expenses = repository.importedExpenses(chamaId, batchId);
        for (Expense expense : expenses) {
            if (expense.paymentEntryId() != null) {
                ledger.reverseOnOriginalDate(chamaId, expense.paymentEntryId(), actorUserId, UNDO_REASON);
            }
        }
        repository.deleteImported(chamaId, batchId);
        auditTrail.record(AuditEntry.of(chamaId, actorUserId, "EXPENSES_IMPORT_UNDONE", "IMPORT", batchId)
                .with(Map.of("expenses", expenses.size())));
        return expenses.size();
    }

    private static LedgerAccountKey accountFor(String method) {
        return switch (method) {
            case "BANK" -> LedgerAccountKey.BANK;
            case "MPESA" -> LedgerAccountKey.MOBILE_MONEY;
            default -> LedgerAccountKey.CASH;
        };
    }
}
