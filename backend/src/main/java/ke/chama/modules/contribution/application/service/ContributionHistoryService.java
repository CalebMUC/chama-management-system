package ke.chama.modules.contribution.application.service;

import ke.chama.modules.account.api.MemberAccountSummary;
import ke.chama.modules.account.api.MemberAccounts;
import ke.chama.modules.account.api.SavingsProductSummary;
import ke.chama.modules.account.api.SavingsProducts;
import ke.chama.modules.audit.api.AuditEntry;
import ke.chama.modules.audit.api.AuditTrail;
import ke.chama.modules.chama.api.ChamaDirectory;
import ke.chama.modules.chama.api.ContributionRules;
import ke.chama.modules.contribution.api.ContributionHistory;
import ke.chama.modules.contribution.application.port.out.ContributionRepository;
import ke.chama.modules.contribution.application.port.out.ContributionRepository.DueRow;
import ke.chama.modules.contribution.domain.exception.ContributionExceptions.DuplicateReferenceException;
import ke.chama.modules.contribution.domain.exception.ContributionExceptions.InvalidPaymentException;
import ke.chama.modules.contribution.domain.exception.ContributionExceptions.NotASeatedMemberException;
import ke.chama.modules.contribution.domain.model.ContributionPayment;
import ke.chama.modules.contribution.domain.model.ContributionPeriod;
import ke.chama.modules.contribution.domain.model.MemberStanding;
import ke.chama.modules.contribution.domain.model.MemberStanding.Due;
import ke.chama.modules.contribution.domain.model.MemberStanding.PeriodStanding;
import ke.chama.modules.contribution.domain.model.PaymentAllocation;
import ke.chama.modules.contribution.domain.model.PaymentAllocation.Allocation;
import ke.chama.modules.contribution.domain.model.PaymentAllocation.Outstanding;
import ke.chama.modules.contribution.domain.model.PaymentMethod;
import ke.chama.modules.contribution.domain.model.PeriodSchedule;
import ke.chama.modules.contribution.domain.model.PeriodSchedule.Frequency;
import ke.chama.modules.contribution.domain.model.PeriodSchedule.PeriodSpan;
import ke.chama.modules.member.api.MemberDirectory;
import ke.chama.modules.member.api.MemberSummary;
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
import java.util.Collection;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Contribution history brought over from a chama's spreadsheets, in the importer's transaction.
 * Payments are split and posted exactly as {@link ContributionService#record} does, but in bulk
 * (everything loaded once) and without receipts.
 */
@Service
class ContributionHistoryService implements ContributionHistory {

    static final String AUDIT_TARGET = "IMPORT";
    static final String UNDO_REASON = "Historical import undone";
    /** Guards against a runaway schedule: about 40 years of months. */
    static final int MAX_PERIODS = 2_100;

    private final ContributionRepository repository;
    private final PeriodOpener periods;
    private final ChamaDirectory chamas;
    private final MemberDirectory members;
    private final SavingsProducts savingsProducts;
    private final MemberAccounts memberAccounts;
    private final Ledger ledger;
    private final AuditTrail auditTrail;
    private final Clock clock;
    private final ZoneId zone;

    ContributionHistoryService(ContributionRepository repository, PeriodOpener periods, ChamaDirectory chamas,
                               MemberDirectory members, SavingsProducts savingsProducts, MemberAccounts memberAccounts,
                               Ledger ledger, AuditTrail auditTrail, Clock clock,
                               @Value("${chama.time-zone:Africa/Nairobi}") String timeZone) {
        this.repository = repository;
        this.periods = periods;
        this.chamas = chamas;
        this.members = members;
        this.savingsProducts = savingsProducts;
        this.memberAccounts = memberAccounts;
        this.ledger = ledger;
        this.auditTrail = auditTrail;
        this.clock = clock;
        this.zone = ZoneId.of(timeZone);
    }

    // ---- periods ------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<PlannedPeriod> plannedPeriods(UUID chamaId, List<ScheduledAmount> schedule) {
        if (schedule.isEmpty()) {
            return List.of();
        }
        ContributionRules rules = rules(chamaId);
        Frequency frequency = Frequency.valueOf(rules.frequency());
        LocalDate stopAt = repository.periods(chamaId).stream().findFirst().map(ContributionPeriod::startDate)
                .orElse(PeriodSchedule.containing(today(), frequency, rules.dueDay()).start());
        UUID defaultProduct = savingsProducts.defaultProduct(chamaId).id();
        LocalDate first = schedule.stream().map(ScheduledAmount::effectiveFrom).min(LocalDate::compareTo).orElseThrow();

        List<PlannedPeriod> planned = new ArrayList<>();
        PeriodSpan span = PeriodSchedule.containing(first, frequency, rules.dueDay());
        for (int i = 0; i < MAX_PERIODS && span.end().isBefore(stopAt); i++) {
            Map<UUID, Long> asks = asksOn(schedule, span.due());
            if (asks.getOrDefault(defaultProduct, 0L) > 0) {
                long total = asks.values().stream().mapToLong(Long::longValue).sum();
                planned.add(new PlannedPeriod(span.key(), span.start(), span.end(), span.due(), total, asks));
            }
            span = PeriodSchedule.after(span.end(), frequency, rules.dueDay());
        }
        return planned;
    }

    /** What each product asks for a period due on {@code due}: its latest amount effective by then, if not zero. */
    static Map<UUID, Long> asksOn(List<ScheduledAmount> schedule, LocalDate due) {
        Map<UUID, ScheduledAmount> latest = new LinkedHashMap<>();
        schedule.stream()
                .filter(s -> !s.effectiveFrom().isAfter(due))
                .sorted(Comparator.comparing(ScheduledAmount::effectiveFrom))
                .forEach(s -> latest.put(s.productId(), s));
        Map<UUID, Long> asks = new LinkedHashMap<>();
        latest.forEach((product, s) -> {
            if (s.amountMinor() > 0) {
                asks.put(product, s.amountMinor());
            }
        });
        return asks;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int backfill(UUID chamaId, List<ScheduledAmount> schedule, UUID batchId) {
        periods.bringUpToDate(chamaId, zone);
        String currency = rules(chamaId).currency();
        Frequency frequency = Frequency.valueOf(rules(chamaId).frequency());
        Instant now = clock.instant();
        int created = 0;
        for (PlannedPeriod p : plannedPeriods(chamaId, schedule)) {
            ContributionPeriod period = ContributionPeriod.open(chamaId,
                    new PeriodSpan(p.key(), frequency, p.startDate(), p.endDate(), p.dueDate()), p.amountDueMinor(),
                    currency, now);
            if (repository.insertHistoricalPeriod(period, p.amountPerProduct(), batchId)) {
                created++;
            }
        }
        // Dues for every account open on each period's due date, old periods and current ones alike.
        periods.bringUpToDate(chamaId, zone);
        return created;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void removeBackfill(UUID chamaId, UUID batchId, Collection<UUID> restoredAccountIds) {
        repository.deleteBatchPeriods(chamaId, batchId);
        Set<UUID> restored = new HashSet<>(restoredAccountIds);
        for (MemberAccountSummary account : memberAccounts.accounts(chamaId)) {
            if (restored.contains(account.id())) {
                repository.deleteDuesBefore(chamaId, account.memberUserId(), account.productId(), account.openedOn());
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasHistoricalPeriods(UUID chamaId) {
        return !repository.historicalPeriodIds(chamaId).isEmpty();
    }

    // ---- payments -----------------------------------------------------------------------------

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int importPayments(UUID chamaId, UUID actorUserId, UUID batchId, List<HistoricalPayment> payments) {
        if (payments.isEmpty()) {
            return 0;
        }
        periods.bringUpToDate(chamaId, zone);
        ContributionRules rules = rules(chamaId);
        Currency currency = Currency.getInstance(rules.currency());
        LocalDate today = today();
        Instant now = clock.instant();

        Map<UUID, SavingsProductSummary> products = savingsProducts.products(chamaId).stream()
                .collect(Collectors.toMap(SavingsProductSummary::id, Function.identity()));
        SavingsProductSummary defaultProduct = savingsProducts.defaultProduct(chamaId);
        Map<UUID, MemberSummary> seated = members.seatedMembers(chamaId).stream()
                .collect(Collectors.toMap(MemberSummary::userId, Function.identity()));
        Map<UUID, Set<UUID>> openAccounts = new HashMap<>();
        for (MemberAccountSummary a : memberAccounts.accounts(chamaId)) {
            if (a.active()) {
                openAccounts.computeIfAbsent(a.memberUserId(), k -> new HashSet<>()).add(a.productId());
            }
        }
        Map<UUID, ContributionPeriod> periodById = repository.periods(chamaId).stream()
                .collect(Collectors.toMap(ContributionPeriod::id, Function.identity()));
        Map<UUID, Map<UUID, List<Due>>> dues = new HashMap<>();
        for (DueRow row : repository.dues(chamaId)) {
            dues.computeIfAbsent(row.memberUserId(), k -> new HashMap<>())
                    .computeIfAbsent(row.productId(), k -> new ArrayList<>())
                    .add(new Due(periodById.get(row.periodId()), row.amountMinor()));
        }
        Map<UUID, Map<UUID, Long>> paid = new HashMap<>();
        repository.paidTotals(chamaId).forEach((member, totals) -> paid.put(member, new HashMap<>(totals)));
        Set<String> references = new HashSet<>(repository.recordedReferences(chamaId));

        List<HistoricalPayment> ordered = payments.stream()
                .sorted(Comparator.comparing(HistoricalPayment::paidOn)).toList();
        List<UUID> ids = new ArrayList<>(ordered.size());
        long total = 0;
        for (HistoricalPayment h : ordered) {
            MemberSummary member = Optional.ofNullable(seated.get(h.memberUserId()))
                    .orElseThrow(NotASeatedMemberException::new);
            PaymentMethod method = method(h.method());
            ContributionPayment payment = ContributionPayment.record(chamaId, member.userId(), h.amountMinor(),
                    rules.currency(), method, h.reference(), h.paidOn(), h.notes(), actorUserId, today, now);
            if (payment.reference() != null && !references.add(method.name() + ":" + payment.reference())) {
                throw new DuplicateReferenceException(payment.reference());
            }
            Map<UUID, Long> memberPaid = paid.computeIfAbsent(member.userId(), k -> new HashMap<>());
            Set<UUID> open = openAccounts.getOrDefault(member.userId(), Set.of());
            List<Allocation> split = h.productId() == null
                    ? autoSplit(payment.amountMinor(), dues.getOrDefault(member.userId(), Map.of()), memberPaid, open,
                    products, defaultProduct.id(), today, rules.lateGraceDays())
                    : List.of(explicit(h.productId(), payment.amountMinor(), open, products));

            List<PostingLine> lines = new ArrayList<>();
            lines.add(PostingLine.debit(accountFor(method), Money.of(payment.amountMinor(), currency)));
            for (Allocation a : split) {
                SavingsProductSummary product = products.get(a.productId());
                lines.add(new PostingLine(null, product.ledgerAccountCode(), EntrySide.CREDIT,
                        Money.of(a.amountMinor(), currency), member.userId(), split.size() > 1 ? product.name() : null));
            }
            UUID entryId = ledger.post(new PostingRequest(chamaId, payment.paidOn(),
                    "Contribution from " + nameOf(member) + " (member #" + member.memberNumber() + ")",
                    payment.reference(), ContributionService.SOURCE_TYPE, payment.id().toString(), actorUserId, lines));
            payment.postedAs(entryId);
            repository.insertPayment(payment, split);
            split.forEach(a -> memberPaid.merge(a.productId(), a.amountMinor(), Long::sum));
            ids.add(payment.id());
            total += payment.amountMinor();
        }
        repository.markImported(chamaId, ids, batchId);
        auditTrail.record(AuditEntry.of(chamaId, actorUserId, "CONTRIBUTIONS_IMPORTED", AUDIT_TARGET, batchId)
                .with(Map.of("payments", ids.size(), "totalMinor", total,
                        "from", ordered.getFirst().paidOn().toString(), "to", ordered.getLast().paidOn().toString())));
        return ids.size();
    }

    /** Oldest dues first, by product priority within a period, among the member's open accounts. */
    private static List<Allocation> autoSplit(long amountMinor, Map<UUID, List<Due>> dues, Map<UUID, Long> paid,
                                              Set<UUID> open, Map<UUID, SavingsProductSummary> products,
                                              UUID defaultProduct, LocalDate today, int graceDays) {
        List<Outstanding> outstanding = new ArrayList<>();
        dues.forEach((productId, productDues) -> {
            SavingsProductSummary product = products.get(productId);
            if (product == null || !product.active() || !open.contains(productId)) {
                return;
            }
            MemberStanding standing = MemberStanding.of(productDues, paid.getOrDefault(productId, 0L), today, graceDays);
            for (PeriodStanding ps : standing.periods()) {
                if (ps.outstandingMinor() > 0) {
                    outstanding.add(new Outstanding(productId, product.allocationPriority(), ps.period().dueDate(),
                            ps.period().startDate(), ps.outstandingMinor()));
                }
            }
        });
        return PaymentAllocation.allocate(amountMinor, outstanding, defaultProduct);
    }

    private static Allocation explicit(UUID productId, long amountMinor, Set<UUID> open,
                                       Map<UUID, SavingsProductSummary> products) {
        SavingsProductSummary product = products.get(productId);
        if (product == null) {
            throw new InvalidPaymentException("Unknown savings product " + productId);
        }
        if (!product.active()) {
            throw new InvalidPaymentException(product.name() + " is no longer offered");
        }
        if (!open.contains(productId)) {
            throw new InvalidPaymentException("The member has no open account in " + product.name());
        }
        return new Allocation(productId, amountMinor);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int undoPayments(UUID chamaId, UUID actorUserId, UUID batchId) {
        List<ContributionPayment> payments = repository.importedPayments(chamaId, batchId);
        Instant now = clock.instant();
        for (ContributionPayment payment : payments) {
            UUID reversal = ledger.reverseOnOriginalDate(chamaId, payment.ledgerEntryId(), actorUserId, UNDO_REASON);
            payment.reverse(actorUserId, UNDO_REASON, reversal, now);
            if (!repository.updateReversal(payment)) {
                throw new OptimisticLockingFailureException("Contribution payment " + payment.id() + " changed concurrently");
            }
        }
        auditTrail.record(AuditEntry.of(chamaId, actorUserId, "CONTRIBUTIONS_IMPORT_UNDONE", AUDIT_TARGET, batchId)
                .with(Map.of("payments", payments.size())));
        return payments.size();
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> usedReferences(UUID chamaId) {
        return repository.recordedReferences(chamaId);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Standing> standings(UUID chamaId) {
        LocalDate today = today();
        Map<UUID, LocalDate> dueDates = repository.periods(chamaId).stream()
                .collect(Collectors.toMap(ContributionPeriod::id, ContributionPeriod::dueDate));
        Map<UUID, Long> due = new HashMap<>();
        for (DueRow row : repository.dues(chamaId)) {
            LocalDate date = dueDates.get(row.periodId());
            if (date != null && !date.isAfter(today)) {
                due.merge(row.memberUserId(), row.amountMinor(), Long::sum);
            }
        }
        Map<UUID, Long> paid = new HashMap<>();
        repository.paidTotals(chamaId).forEach((member, totals) ->
                paid.put(member, totals.values().stream().mapToLong(Long::longValue).sum()));
        Set<UUID> seated = members.seatedMembers(chamaId).stream().map(MemberSummary::userId).collect(Collectors.toSet());
        Map<UUID, Standing> result = new HashMap<>();
        for (UUID member : seated) {
            long d = due.getOrDefault(member, 0L);
            long p = paid.getOrDefault(member, 0L);
            if (d > 0 || p > 0) {
                result.put(member, new Standing(d, p));
            }
        }
        return result;
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static PaymentMethod method(String method) {
        try {
            return PaymentMethod.valueOf(method == null ? "" : method.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidPaymentException("method must be CASH, BANK or MPESA");
        }
    }

    private static LedgerAccountKey accountFor(PaymentMethod method) {
        return switch (method) {
            case CASH -> LedgerAccountKey.CASH;
            case BANK -> LedgerAccountKey.BANK;
            case MPESA -> LedgerAccountKey.MOBILE_MONEY;
        };
    }

    private static String nameOf(MemberSummary member) {
        String name = member.fullName().trim();
        return name.isEmpty() ? member.email() : name;
    }

    private ContributionRules rules(UUID chamaId) {
        return chamas.contributionRules(chamaId).orElseThrow();
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(zone));
    }
}
