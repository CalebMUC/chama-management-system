package ke.chama.modules.penalty.application.service;

import ke.chama.modules.audit.api.AuditEntry;
import ke.chama.modules.audit.api.AuditTrail;
import ke.chama.modules.chama.api.ChamaDirectory;
import ke.chama.modules.chama.api.ChamaSummary;
import ke.chama.modules.penalty.api.PenaltyHistory;
import ke.chama.modules.penalty.application.port.out.PenaltyRepository;
import ke.chama.modules.penalty.domain.model.Penalty;
import ke.chama.modules.penalty.domain.model.PenaltyPayment;
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
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Penalties brought over from a chama's spreadsheets, posted exactly as assessing, paying and waiving by hand would. */
@Service
class PenaltyHistoryService implements PenaltyHistory {

    static final String UNDO_REASON = "Historical import undone";

    private final PenaltyRepository repository;
    private final ChamaDirectory chamas;
    private final Ledger ledger;
    private final AuditTrail auditTrail;
    private final Clock clock;
    private final ZoneId zone;

    PenaltyHistoryService(PenaltyRepository repository, ChamaDirectory chamas, Ledger ledger, AuditTrail auditTrail,
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
    public int importPenalties(UUID chamaId, UUID actorUserId, UUID batchId, List<HistoricalPenalty> penalties) {
        String currency = chamas.find(chamaId).map(ChamaSummary::currency).orElse("KES");
        LocalDate today = LocalDate.now(clock.withZone(zone));
        Instant now = clock.instant();
        List<UUID> penaltyIds = new ArrayList<>();
        List<UUID> paymentIds = new ArrayList<>();
        long assessed = 0;
        for (HistoricalPenalty h : penalties) {
            Penalty penalty = Penalty.historical(chamaId, h.memberUserId(), Penalty.Kind.valueOf(h.kind()),
                    "IMPORT:" + batchId.toString().substring(0, 8) + ":" + h.sourceRef(), h.description(),
                    h.amountMinor(), currency, h.assessedOn(), actorUserId, now);
            Money amount = money(penalty.amountMinor(), currency);
            penalty.postedAs(ledger.post(new PostingRequest(chamaId, h.assessedOn(), penalty.description(), null,
                    PenaltyService.SOURCE_ASSESSMENT, penalty.id().toString(), actorUserId, List.of(
                    PostingLine.debit(LedgerAccountKey.PENALTIES_RECEIVABLE, amount).forMember(penalty.memberUserId()),
                    PostingLine.credit(LedgerAccountKey.PENALTY_INCOME, amount)))));
            repository.insert(penalty);

            for (HistoricalPayment p : h.payments().stream().sorted(Comparator.comparing(HistoricalPayment::paidOn)).toList()) {
                PenaltyPayment payment = PenaltyPayment.record(penalty, p.amountMinor(), p.method(), p.reference(),
                        p.paidOn(), actorUserId, today, now);
                penalty.pay(payment.amountMinor());
                Money paid = money(payment.amountMinor(), currency);
                UUID entry = ledger.post(new PostingRequest(chamaId, payment.paidOn(),
                        "Penalty payment: " + penalty.description(), payment.reference(), PenaltyService.SOURCE_PAYMENT,
                        payment.id().toString(), actorUserId, List.of(
                        PostingLine.debit(accountFor(payment.method()), paid),
                        PostingLine.credit(LedgerAccountKey.PENALTIES_RECEIVABLE, paid).forMember(penalty.memberUserId()))));
                repository.insertPayment(payment.postedAs(entry));
                paymentIds.add(payment.id());
            }
            if (h.waivedOn() != null && penalty.outstandingMinor() > 0) {
                long waived = penalty.waive(actorUserId, h.waiverReason(), h.waivedOn().atStartOfDay(zone).toInstant());
                Money w = money(waived, currency);
                penalty.waiverPostedAs(ledger.post(new PostingRequest(chamaId, h.waivedOn(),
                        "Penalty waived: " + penalty.description(), null, PenaltyService.SOURCE_WAIVER,
                        penalty.id().toString(), actorUserId, List.of(
                        PostingLine.debit(LedgerAccountKey.PENALTY_INCOME, w),
                        PostingLine.credit(LedgerAccountKey.PENALTIES_RECEIVABLE, w).forMember(penalty.memberUserId())))));
            }
            if (!repository.update(penalty)) {
                throw new OptimisticLockingFailureException("Penalty " + penalty.id() + " changed concurrently");
            }
            penaltyIds.add(penalty.id());
            assessed += penalty.amountMinor();
        }
        repository.markImported(chamaId, penaltyIds, paymentIds, batchId);
        auditTrail.record(AuditEntry.of(chamaId, actorUserId, "PENALTIES_IMPORTED", "IMPORT", batchId)
                .with(Map.of("penalties", penaltyIds.size(), "payments", paymentIds.size(), "assessedMinor", assessed)));
        return penaltyIds.size();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> undoBlocked(UUID chamaId, UUID batchId) {
        long later = repository.laterPayments(chamaId, batchId);
        return later == 0 ? Optional.empty() : Optional.of(later + " payment" + (later == 1 ? " was" : "s were")
                + " recorded on these penalties since they were imported, so the import stays.");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int undoPenalties(UUID chamaId, UUID actorUserId, UUID batchId) {
        List<Penalty> penalties = repository.importedPenalties(chamaId, batchId);
        for (Penalty penalty : penalties) {
            if (penalty.waiverEntryId() != null) {
                ledger.reverseOnOriginalDate(chamaId, penalty.waiverEntryId(), actorUserId, UNDO_REASON);
            }
            for (PenaltyPayment p : repository.payments(chamaId, penalty.id())) {
                ledger.reverseOnOriginalDate(chamaId, p.ledgerEntryId(), actorUserId, UNDO_REASON);
            }
            ledger.reverseOnOriginalDate(chamaId, penalty.assessmentEntryId(), actorUserId, UNDO_REASON);
        }
        repository.deleteImported(chamaId, batchId);
        auditTrail.record(AuditEntry.of(chamaId, actorUserId, "PENALTIES_IMPORT_UNDONE", "IMPORT", batchId)
                .with(Map.of("penalties", penalties.size())));
        return penalties.size();
    }

    private static LedgerAccountKey accountFor(String method) {
        return switch (method) {
            case "BANK" -> LedgerAccountKey.BANK;
            case "MPESA" -> LedgerAccountKey.MOBILE_MONEY;
            default -> LedgerAccountKey.CASH;
        };
    }

    private static Money money(long minor, String currency) {
        return Money.of(minor, Currency.getInstance(currency));
    }
}
