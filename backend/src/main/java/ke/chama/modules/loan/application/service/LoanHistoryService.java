package ke.chama.modules.loan.application.service;

import ke.chama.modules.audit.api.AuditEntry;
import ke.chama.modules.audit.api.AuditTrail;
import ke.chama.modules.chama.api.ChamaDirectory;
import ke.chama.modules.chama.api.ChamaSummary;
import ke.chama.modules.loan.api.LoanHistory;
import ke.chama.modules.loan.application.port.out.LoanProductRepository;
import ke.chama.modules.loan.application.port.out.LoanRepository;
import ke.chama.modules.loan.domain.model.Loan;
import ke.chama.modules.loan.domain.model.Loan.Instalment;
import ke.chama.modules.loan.domain.model.LoanRepayment;
import ke.chama.modules.transaction.api.Ledger;
import ke.chama.modules.transaction.api.LedgerAccountKey;
import ke.chama.modules.transaction.api.PostingRequest;
import ke.chama.modules.transaction.api.PostingRequest.PostingLine;
import ke.chama.shared.domain.Money;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Loans brought over from a chama's spreadsheets, posted exactly as disbursing and repaying by hand would. */
@Service
class LoanHistoryService implements LoanHistory {

    static final String UNDO_REASON = "Historical import undone";

    private final LoanRepository repository;
    private final LoanProductRepository products;
    private final LoanProductService productService;
    private final ChamaDirectory chamas;
    private final Ledger ledger;
    private final AuditTrail auditTrail;
    private final Clock clock;
    private final ZoneId zone;

    LoanHistoryService(LoanRepository repository, LoanProductRepository products, LoanProductService productService,
                       ChamaDirectory chamas, Ledger ledger, AuditTrail auditTrail, Clock clock,
                       @Value("${chama.time-zone:Africa/Nairobi}") String timeZone) {
        this.repository = repository;
        this.products = products;
        this.productService = productService;
        this.chamas = chamas;
        this.ledger = ledger;
        this.auditTrail = auditTrail;
        this.clock = clock;
        this.zone = ZoneId.of(timeZone);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Product> products(UUID chamaId) {
        return products.products(chamaId).stream()
                .map(p -> new Product(p.id(), p.code(), p.name(), p.interestBasisPoints(), p.maxTermMonths(),
                        p.defaultProduct(), p.active()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> usedReferences(UUID chamaId) {
        return repository.usedReferences(chamaId);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Set<UUID>> openLoans(UUID chamaId) {
        Map<UUID, Set<UUID>> open = new HashMap<>();
        for (Loan loan : repository.allLoans(chamaId)) {
            if (loan.status().open()) {
                open.computeIfAbsent(loan.borrowerUserId(), k -> new HashSet<>()).add(loan.productId());
            }
        }
        return open;
    }

    @Override
    @Transactional(readOnly = true)
    public Projection project(UUID chamaId, HistoricalLoan h) {
        LocalDate today = today();
        Loan loan = Loan.historical(UUID.randomUUID(), chamaId, h.borrowerUserId(), productId(chamaId, h),
                h.principalMinor(), h.interestMinor(), h.termMonths(), h.disbursedOn(), h.repaidMinor(), h.purpose(),
                currency(chamaId), h.method(), null, null, UUID.randomUUID(), clock.instant());
        Optional<Instalment> next = loan.nextInstalment(today);
        int overdue = (int) loan.schedule(today).stream().filter(Instalment::overdue).count();
        return new Projection(loan.status().name(), loan.totalDueMinor(), loan.repaidMinor(), loan.outstandingMinor(),
                loan.arrearsMinor(today), overdue, next.map(Instalment::dueDate).orElse(null),
                next.map(Instalment::unpaidMinor).orElse(0L), loan.maturityDate());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID> importLoans(UUID chamaId, UUID actorUserId, UUID batchId, List<HistoricalLoan> loans) {
        String currencyCode = currency(chamaId);
        Currency currency = Currency.getInstance(currencyCode);
        LocalDate today = today();
        Instant now = clock.instant();
        List<UUID> loanIds = new ArrayList<>();
        List<UUID> repaymentIds = new ArrayList<>();
        long principal = 0;
        for (HistoricalLoan h : loans) {
            UUID id = UUID.randomUUID();
            String reference = Loan.validPayment(h.method(), h.reference(), h.disbursedOn(), today);
            long total = h.principalMinor() + h.interestMinor();
            List<PostingLine> lines = new ArrayList<>();
            lines.add(PostingLine.debit(LedgerAccountKey.LOANS_RECEIVABLE, Money.of(total, currency))
                    .forMember(h.borrowerUserId()));
            lines.add(PostingLine.credit(accountFor(h.method()), Money.of(h.principalMinor(), currency)));
            if (h.interestMinor() > 0) {
                lines.add(PostingLine.credit(LedgerAccountKey.INTEREST_INCOME, Money.of(h.interestMinor(), currency)));
            }
            String purpose = h.purpose() == null || h.purpose().isBlank() ? "Loan from the chama's records" : h.purpose().trim();
            UUID entryId = ledger.post(new PostingRequest(chamaId, h.disbursedOn(), "Loan disbursed: " + purpose,
                    reference, LoanService.SOURCE_DISBURSEMENT, id.toString(), actorUserId, lines));
            Loan loan = Loan.historical(id, chamaId, h.borrowerUserId(), productId(chamaId, h), h.principalMinor(),
                    h.interestMinor(), h.termMonths(), h.disbursedOn(), h.repaidMinor(), purpose, currencyCode,
                    h.method(), reference, actorUserId, entryId, now);
            repository.insert(loan);

            for (HistoricalRepayment r : h.repayments().stream()
                    .sorted(Comparator.comparing(HistoricalRepayment::paidOn)).toList()) {
                LoanRepayment repayment = LoanRepayment.record(loan, r.amountMinor(), r.method(), r.reference(),
                        r.paidOn(), actorUserId, today, now);
                Money amount = Money.of(repayment.amountMinor(), currency);
                UUID repaymentEntry = ledger.post(new PostingRequest(chamaId, repayment.paidOn(),
                        "Loan repayment: " + loan.purpose(), repayment.reference(), LoanService.SOURCE_REPAYMENT,
                        repayment.id().toString(), actorUserId, List.of(
                        PostingLine.debit(accountFor(repayment.method()), amount),
                        PostingLine.credit(LedgerAccountKey.LOANS_RECEIVABLE, amount).forMember(loan.borrowerUserId()))));
                repository.insertRepayment(repayment.postedAs(repaymentEntry));
                repaymentIds.add(repayment.id());
            }
            loanIds.add(id);
            principal += h.principalMinor();
        }
        repository.markImported(chamaId, loanIds, repaymentIds, batchId);
        auditTrail.record(AuditEntry.of(chamaId, actorUserId, "LOANS_IMPORTED", "IMPORT", batchId)
                .with(Map.of("loans", loanIds.size(), "repayments", repaymentIds.size(), "principalMinor", principal)));
        return loanIds;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> undoBlocked(UUID chamaId, UUID batchId) {
        long later = repository.laterRepayments(chamaId, batchId);
        return later == 0 ? Optional.empty() : Optional.of(later + " repayment" + (later == 1 ? " was" : "s were")
                + " recorded on these loans since they were imported. Reverse "
                + (later == 1 ? "it" : "them") + " in the ledger first, or keep this import.");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int undoLoans(UUID chamaId, UUID actorUserId, UUID batchId) {
        List<Loan> loans = repository.importedLoans(chamaId, batchId);
        for (Loan loan : loans) {
            for (LoanRepayment r : repository.repayments(chamaId, loan.id())) {
                ledger.reverseOnOriginalDate(chamaId, r.ledgerEntryId(), actorUserId, UNDO_REASON);
            }
            ledger.reverseOnOriginalDate(chamaId, loan.disbursementEntryId(), actorUserId, UNDO_REASON);
        }
        repository.deleteImported(chamaId, batchId);
        auditTrail.record(AuditEntry.of(chamaId, actorUserId, "LOANS_IMPORT_UNDONE", "IMPORT", batchId)
                .with(Map.of("loans", loans.size())));
        return loans.size();
    }

    // ---- helpers --------------------------------------------------------------------------------

    private UUID productId(UUID chamaId, HistoricalLoan h) {
        return h.productId() != null ? h.productId() : productService.defaultProduct(chamaId).id();
    }

    private static LedgerAccountKey accountFor(String method) {
        return switch (method) {
            case "BANK" -> LedgerAccountKey.BANK;
            case "MPESA" -> LedgerAccountKey.MOBILE_MONEY;
            default -> LedgerAccountKey.CASH;
        };
    }

    private String currency(UUID chamaId) {
        return chamas.find(chamaId).map(ChamaSummary::currency).orElse("KES");
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(zone));
    }
}
