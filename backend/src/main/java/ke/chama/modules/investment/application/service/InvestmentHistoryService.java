package ke.chama.modules.investment.application.service;

import ke.chama.modules.audit.api.AuditEntry;
import ke.chama.modules.audit.api.AuditTrail;
import ke.chama.modules.chama.api.ChamaDirectory;
import ke.chama.modules.chama.api.ChamaSummary;
import ke.chama.modules.investment.api.InvestmentHistory;
import ke.chama.modules.investment.application.port.out.InvestmentRepository;
import ke.chama.modules.investment.domain.model.Investment;
import ke.chama.modules.investment.domain.model.InvestmentTransaction;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Investments brought over from a chama's spreadsheets: funded, then replayed movement by movement
 * through the same rules and postings as recording each by hand.
 */
@Service
class InvestmentHistoryService implements InvestmentHistory {

    static final String UNDO_REASON = "Historical import undone";
    /** On the same day: money in first, then out, then valuations, the closure last. */
    static final List<String> ORDER = List.of("DEPOSIT", "INCOME", "WITHDRAWAL", "VALUATION", "CLOSURE");

    private final InvestmentRepository repository;
    private final ChamaDirectory chamas;
    private final Ledger ledger;
    private final AuditTrail auditTrail;
    private final Clock clock;
    private final ZoneId zone;

    InvestmentHistoryService(InvestmentRepository repository, ChamaDirectory chamas, Ledger ledger,
                             AuditTrail auditTrail, Clock clock,
                             @Value("${chama.time-zone:Africa/Nairobi}") String timeZone) {
        this.repository = repository;
        this.chamas = chamas;
        this.ledger = ledger;
        this.auditTrail = auditTrail;
        this.clock = clock;
        this.zone = ZoneId.of(timeZone);
    }

    @Override
    public Set<String> typeNames() {
        return Arrays.stream(Investment.Type.values()).map(Enum::name).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> usedReferences(UUID chamaId) {
        return repository.usedReferences(chamaId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int importInvestments(UUID chamaId, UUID actorUserId, UUID batchId, List<HistoricalInvestment> investments) {
        String currencyCode = chamas.find(chamaId).map(ChamaSummary::currency).orElse("KES");
        Currency currency = Currency.getInstance(currencyCode);
        LocalDate today = LocalDate.now(clock.withZone(zone));
        Instant now = clock.instant();
        List<UUID> ids = new ArrayList<>();
        for (HistoricalInvestment h : investments) {
            Investment investment = Investment.historical(chamaId, actorUserId, h.name(),
                    Investment.Type.valueOf(h.type().trim().toUpperCase(Locale.ROOT)), h.institution(), h.description(),
                    h.amountMinor(), currencyCode, h.expectedReturnBasisPoints(), h.maturityDate(), now);
            repository.insert(investment);

            InvestmentTransaction funding = investment.fund(actorUserId, h.method(), h.reference(), h.fundedOn(), today, now);
            Money funded = Money.of(funding.amountMinor(), currency);
            post(investment, funding, "Investment funded: " + investment.name(), List.of(
                    PostingLine.debit(LedgerAccountKey.INVESTMENTS, funded),
                    PostingLine.credit(accountFor(funding.method()), funded)));

            List<HistoricalMovement> movements = h.movements().stream()
                    .sorted(Comparator.comparing(HistoricalMovement::date)
                            .thenComparingInt(m -> ORDER.indexOf(m.kind())))
                    .toList();
            for (HistoricalMovement m : movements) {
                apply(investment, m, actorUserId, currency, today, now);
            }
            if (!repository.update(investment)) {
                throw new OptimisticLockingFailureException("Investment " + investment.id() + " changed concurrently");
            }
            ids.add(investment.id());
        }
        repository.markImported(chamaId, ids, batchId);
        auditTrail.record(AuditEntry.of(chamaId, actorUserId, "INVESTMENTS_IMPORTED", "IMPORT", batchId)
                .with(Map.of("investments", ids.size())));
        return ids.size();
    }

    private void apply(Investment investment, HistoricalMovement m, UUID by, Currency currency, LocalDate today,
                       Instant now) {
        switch (m.kind()) {
            case "DEPOSIT" -> {
                InvestmentTransaction t = investment.deposit(by, m.amountMinor(), m.method(), m.reference(), m.date(),
                        m.note(), today, now);
                Money amount = Money.of(t.amountMinor(), currency);
                post(investment, t, "Deposit to investment: " + investment.name(), List.of(
                        PostingLine.debit(LedgerAccountKey.INVESTMENTS, amount),
                        PostingLine.credit(accountFor(t.method()), amount)));
            }
            case "INCOME" -> {
                InvestmentTransaction t = investment.recordIncome(by, m.amountMinor(), m.method(), m.reference(),
                        m.date(), m.note(), today, now);
                Money amount = Money.of(t.amountMinor(), currency);
                post(investment, t, "Investment income: " + investment.name(), List.of(
                        PostingLine.debit(t.reinvested() ? LedgerAccountKey.INVESTMENTS : accountFor(t.method()), amount),
                        PostingLine.credit(LedgerAccountKey.INVESTMENT_INCOME, amount)));
            }
            case "WITHDRAWAL" -> {
                InvestmentTransaction t = investment.withdraw(by, m.amountMinor(), m.method(), m.reference(), m.date(),
                        m.note(), today, now);
                Money amount = Money.of(t.amountMinor(), currency);
                post(investment, t, "Investment withdrawal: " + investment.name(), List.of(
                        PostingLine.debit(accountFor(t.method()), amount),
                        PostingLine.credit(LedgerAccountKey.INVESTMENTS, amount)));
            }
            case "CLOSURE" -> {
                long bookValue = investment.bookValueMinor();
                InvestmentTransaction t = investment.close(by, m.amountMinor(), m.method(), m.reference(), m.date(),
                        m.note(), today, now);
                long gain = investment.realisedGainMinor();
                List<PostingLine> lines = new ArrayList<>();
                if (t.amountMinor() > 0) {
                    lines.add(PostingLine.debit(accountFor(t.method()), Money.of(t.amountMinor(), currency)));
                }
                if (gain < 0) {
                    lines.add(PostingLine.debit(LedgerAccountKey.INVESTMENT_INCOME, Money.of(-gain, currency))
                            .withMemo("Loss on closing the investment"));
                }
                lines.add(PostingLine.credit(LedgerAccountKey.INVESTMENTS, Money.of(bookValue, currency)));
                if (gain > 0) {
                    lines.add(PostingLine.credit(LedgerAccountKey.INVESTMENT_INCOME, Money.of(gain, currency))
                            .withMemo("Gain on closing the investment"));
                }
                post(investment, t, "Investment closed: " + investment.name(), lines);
            }
            case "VALUATION" -> repository.insertValuation(investment.value(by, m.amountMinor(), m.date(), m.note(),
                    today, now));
            default -> throw new IllegalArgumentException("Unknown movement " + m.kind());
        }
    }

    private void post(Investment investment, InvestmentTransaction transaction, String description,
                      List<PostingLine> lines) {
        UUID entryId = ledger.post(new PostingRequest(investment.chamaId(), transaction.date(), description,
                transaction.reference(), "INVESTMENT_" + transaction.kind().name(), transaction.id().toString(),
                transaction.recordedBy(), lines));
        repository.insertTransaction(transaction.postedAs(entryId));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> undoBlocked(UUID chamaId, UUID batchId) {
        long later = repository.laterActivity(chamaId, batchId);
        return later == 0 ? Optional.empty() : Optional.of(later + " transaction" + (later == 1 ? " or valuation was"
                : "s or valuations were") + " recorded on these investments since they were imported, so the import stays.");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int undoInvestments(UUID chamaId, UUID actorUserId, UUID batchId) {
        List<Investment> investments = repository.importedInvestments(chamaId, batchId);
        for (Investment investment : investments) {
            for (InvestmentTransaction t : repository.transactions(chamaId, investment.id())) {
                if (t.ledgerEntryId() != null) {
                    ledger.reverseOnOriginalDate(chamaId, t.ledgerEntryId(), actorUserId, UNDO_REASON);
                }
            }
        }
        repository.deleteImported(chamaId, batchId);
        auditTrail.record(AuditEntry.of(chamaId, actorUserId, "INVESTMENTS_IMPORT_UNDONE", "IMPORT", batchId)
                .with(Map.of("investments", investments.size())));
        return investments.size();
    }

    private static LedgerAccountKey accountFor(String method) {
        return switch (method) {
            case "BANK" -> LedgerAccountKey.BANK;
            case "MPESA" -> LedgerAccountKey.MOBILE_MONEY;
            default -> LedgerAccountKey.CASH;
        };
    }
}
