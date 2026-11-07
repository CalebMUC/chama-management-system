package ke.chama.modules.contribution.application.service;

import ke.chama.modules.account.api.MemberAccountSummary;
import ke.chama.modules.account.api.MemberAccounts;
import ke.chama.modules.account.api.SavingsProductSummary;
import ke.chama.modules.account.api.SavingsProducts;
import ke.chama.modules.audit.api.AuditEntry;
import ke.chama.modules.audit.api.AuditTrail;
import ke.chama.modules.auth.api.UserDirectory;
import ke.chama.modules.auth.api.UserSummary;
import ke.chama.modules.chama.api.ChamaDirectory;
import ke.chama.modules.chama.api.ChamaSummary;
import ke.chama.modules.chama.api.ContributionRules;
import ke.chama.modules.contribution.api.ContributionCalendar;
import ke.chama.modules.contribution.api.ContributionReports;
import ke.chama.modules.contribution.api.LateContributions;
import ke.chama.modules.contribution.application.port.in.ContributionUseCases;
import ke.chama.modules.contribution.application.port.in.ContributionViews.AllocationView;
import ke.chama.modules.contribution.application.port.in.ContributionViews.MemberPeriodView;
import ke.chama.modules.contribution.application.port.in.ContributionViews.MyContributionsView;
import ke.chama.modules.contribution.application.port.in.ContributionViews.MyPeriodView;
import ke.chama.modules.contribution.application.port.in.ContributionViews.PaymentView;
import ke.chama.modules.contribution.application.port.in.ContributionViews.PeriodDetailView;
import ke.chama.modules.contribution.application.port.in.ContributionViews.PeriodProductView;
import ke.chama.modules.contribution.application.port.in.ContributionViews.PeriodSummaryView;
import ke.chama.modules.contribution.application.port.in.ContributionViews.PeriodView;
import ke.chama.modules.contribution.application.port.in.ContributionViews.ProductStandingView;
import ke.chama.modules.contribution.application.port.in.ContributionViews.StandingView;
import ke.chama.modules.contribution.application.port.out.ContributionRepository;
import ke.chama.modules.contribution.application.port.out.ContributionRepository.DueRow;
import ke.chama.modules.contribution.domain.event.ContributionRecorded;
import ke.chama.modules.contribution.domain.exception.ContributionExceptions.DuplicateReferenceException;
import ke.chama.modules.contribution.domain.exception.ContributionExceptions.InvalidPaymentException;
import ke.chama.modules.contribution.domain.exception.ContributionExceptions.NotASeatedMemberException;
import ke.chama.modules.contribution.domain.exception.ContributionExceptions.PaymentAlreadyReversedException;
import ke.chama.modules.contribution.domain.exception.ContributionExceptions.PaymentNotFoundException;
import ke.chama.modules.contribution.domain.exception.ContributionExceptions.PeriodNotFoundException;
import ke.chama.modules.contribution.domain.exception.ContributionExceptions.SelfRecordingNotAllowedException;
import ke.chama.modules.contribution.domain.model.ContributionPayment;
import ke.chama.modules.contribution.domain.model.ContributionPeriod;
import ke.chama.modules.contribution.domain.model.MemberStanding;
import ke.chama.modules.contribution.domain.model.MemberStanding.Due;
import ke.chama.modules.contribution.domain.model.MemberStanding.PeriodStanding;
import ke.chama.modules.contribution.domain.model.PaymentAllocation;
import ke.chama.modules.contribution.domain.model.PaymentAllocation.Allocation;
import ke.chama.modules.contribution.domain.model.PaymentAllocation.Outstanding;
import ke.chama.modules.contribution.domain.model.PaymentMethod;
import ke.chama.modules.member.api.MemberDirectory;
import ke.chama.modules.member.api.MemberSummary;
import ke.chama.modules.transaction.api.EntrySide;
import ke.chama.modules.transaction.api.Ledger;
import ke.chama.modules.transaction.api.LedgerAccountKey;
import ke.chama.modules.transaction.api.PostingRequest;
import ke.chama.modules.transaction.api.PostingRequest.PostingLine;
import ke.chama.shared.domain.Money;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
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
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Contributions: recording and reversing payments (each posted to the ledger, split across the
 * member's savings products), and where every member stands. Write transactions throughout,
 * because every operation first brings the chama's periods, accounts and dues up to date
 * ({@link PeriodOpener}).
 * <p>
 * A member's standing is worked out per product (that product's dues against what was paid into
 * it) and then added up, so the totals read exactly as they did with a single savings pot.
 */
@Service
@Transactional
public class ContributionService implements ContributionUseCases, LateContributions, ContributionCalendar,
        ContributionReports {

    static final String SOURCE_TYPE = "CONTRIBUTION";
    static final String AUDIT_TARGET = "CONTRIBUTION";
    static final int RECENT_PAYMENTS = 10;

    private final ContributionRepository repository;
    private final PeriodOpener periods;
    private final MemberDirectory members;
    private final UserDirectory users;
    private final ChamaDirectory chamas;
    private final SavingsProducts savingsProducts;
    private final MemberAccounts memberAccounts;
    private final Ledger ledger;
    private final AuditTrail auditTrail;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final ZoneId zone;

    public ContributionService(ContributionRepository repository, PeriodOpener periods, MemberDirectory members,
                               UserDirectory users, ChamaDirectory chamas, SavingsProducts savingsProducts,
                               MemberAccounts memberAccounts, Ledger ledger, AuditTrail auditTrail,
                               ApplicationEventPublisher events, Clock clock,
                               @Value("${chama.time-zone:Africa/Nairobi}") String timeZone) {
        this.repository = repository;
        this.periods = periods;
        this.members = members;
        this.users = users;
        this.chamas = chamas;
        this.savingsProducts = savingsProducts;
        this.memberAccounts = memberAccounts;
        this.ledger = ledger;
        this.auditTrail = auditTrail;
        this.events = events;
        this.clock = clock;
        this.zone = ZoneId.of(timeZone);
    }

    // ---- commands ---------------------------------------------------------------------------

    @Override
    public PaymentView record(UUID chamaId, UUID actorUserId, RecordPayment request) {
        MemberSummary member = members.seatedMember(chamaId, request.memberUserId())
                .orElseThrow(NotASeatedMemberException::new);
        if (member.userId().equals(actorUserId)) {
            throw new SelfRecordingNotAllowedException();
        }
        periods.bringUpToDate(chamaId, zone);
        ContributionRules rules = rules(chamaId);
        Catalog catalog = catalog(chamaId);

        ContributionPayment payment = ContributionPayment.record(chamaId, member.userId(), request.amountMinor(),
                rules.currency(), request.method(), request.reference(), request.paidOn(), request.notes(),
                actorUserId, today(), clock.instant());
        if (payment.reference() != null
                && repository.recordedReferenceExists(chamaId, payment.method(), payment.reference())) {
            throw new DuplicateReferenceException(payment.reference());
        }
        List<Allocation> split = request.allocations() == null
                ? autoAllocation(chamaId, member.userId(), payment.amountMinor(), rules, catalog)
                : explicitAllocation(chamaId, member.userId(), payment.amountMinor(), request.allocations(), catalog);

        Currency currency = Currency.getInstance(payment.currency());
        List<PostingLine> lines = new ArrayList<>();
        lines.add(PostingLine.debit(accountFor(payment.method()), Money.of(payment.amountMinor(), currency)));
        for (Allocation a : split) {
            SavingsProductSummary product = catalog.product(a.productId());
            lines.add(new PostingLine(null, product.ledgerAccountCode(), EntrySide.CREDIT,
                    Money.of(a.amountMinor(), currency), member.userId(), split.size() > 1 ? product.name() : null));
        }
        UUID entryId = ledger.post(new PostingRequest(chamaId, payment.paidOn(),
                "Contribution from " + nameOf(member) + " (member #" + member.memberNumber() + ")",
                payment.reference(), SOURCE_TYPE, payment.id().toString(), actorUserId, lines));
        payment.postedAs(entryId);
        repository.insertPayment(payment, split);

        auditTrail.record(AuditEntry.of(chamaId, actorUserId, "CONTRIBUTION_RECORDED", AUDIT_TARGET, payment.id())
                .with(Map.of("memberUserId", member.userId().toString(), "amountMinor", payment.amountMinor(),
                        "method", payment.method().name(), "reference", payment.reference() == null ? "" : payment.reference(),
                        "allocations", split.stream().map(a -> Map.of("productCode", catalog.product(a.productId()).code(),
                                "amountMinor", a.amountMinor())).toList())));

        MemberStanding standing = MemberStanding.combine(List.copyOf(standingsOf(chamaId, member.userId(), rules).values()));
        long savings = ledger.memberBalance(chamaId, member.userId(), LedgerAccountKey.MEMBER_SAVINGS).minorUnits();
        String chamaName = chamas.find(chamaId).map(ChamaSummary::name).orElse("your chama");
        events.publishEvent(new ContributionRecorded(UUID.randomUUID(), clock.instant(), chamaId, chamaName,
                payment.id(), member.userId(), member.email(), member.firstName(), payment.amountMinor(),
                payment.currency(), payment.method().name(), payment.reference(), payment.paidOn(), savings,
                standing.arrearsMinor()));
        return view(payment, nameOf(member), member.memberNumber(), split, catalog);
    }

    @Override
    public PaymentView reverse(UUID chamaId, UUID actorUserId, UUID paymentId, String reason) {
        ContributionPayment payment = repository.payment(chamaId, paymentId).orElseThrow(PaymentNotFoundException::new);
        if (!payment.isRecorded()) {
            throw new PaymentAlreadyReversedException();
        }
        if (payment.memberUserId().equals(actorUserId)) {
            throw new SelfRecordingNotAllowedException();
        }
        String why = reason == null ? "" : reason.trim();
        UUID reversalEntry = ledger.reverse(chamaId, payment.ledgerEntryId(), actorUserId, "Contribution reversed: " + why);
        payment.reverse(actorUserId, why, reversalEntry, clock.instant());
        if (!repository.updateReversal(payment)) {
            throw new OptimisticLockingFailureException("Contribution payment " + paymentId + " changed concurrently");
        }
        auditTrail.record(AuditEntry.of(chamaId, actorUserId, "CONTRIBUTION_REVERSED", AUDIT_TARGET, paymentId)
                .with(Map.of("memberUserId", payment.memberUserId().toString(), "amountMinor", payment.amountMinor(),
                        "reason", why)));
        return payment(chamaId, paymentId);
    }

    // ---- allocation ---------------------------------------------------------------------------

    /** Oldest dues first, by product priority within a period, among the member's open accounts. */
    private List<Allocation> autoAllocation(UUID chamaId, UUID memberUserId, long amountMinor, ContributionRules rules,
                                            Catalog catalog) {
        Set<UUID> open = memberAccounts.accountsOf(chamaId, memberUserId).stream()
                .filter(MemberAccountSummary::active).map(MemberAccountSummary::productId).collect(Collectors.toSet());
        List<Outstanding> outstanding = new ArrayList<>();
        standingsOf(chamaId, memberUserId, rules).forEach((productId, standing) -> {
            SavingsProductSummary product = catalog.products().get(productId);
            if (product == null || !product.active() || !open.contains(productId)) {
                return;
            }
            for (PeriodStanding ps : standing.periods()) {
                if (ps.outstandingMinor() > 0) {
                    outstanding.add(new Outstanding(productId, product.allocationPriority(), ps.period().dueDate(),
                            ps.period().startDate(), ps.outstandingMinor()));
                }
            }
        });
        return PaymentAllocation.allocate(amountMinor, outstanding, catalog.defaultProduct().id());
    }

    /** The official's own split: into active products the member has an open account in. */
    private List<Allocation> explicitAllocation(UUID chamaId, UUID memberUserId, long amountMinor,
                                                List<Allocation> requested, Catalog catalog) {
        List<Allocation> split = PaymentAllocation.explicit(amountMinor, requested);
        Set<UUID> open = memberAccounts.accountsOf(chamaId, memberUserId).stream()
                .filter(MemberAccountSummary::active).map(MemberAccountSummary::productId).collect(Collectors.toSet());
        for (Allocation a : split) {
            SavingsProductSummary product = catalog.products().get(a.productId());
            if (product == null) {
                throw new InvalidPaymentException("Unknown savings product " + a.productId());
            }
            if (!product.active()) {
                throw new InvalidPaymentException(product.name() + " is no longer offered");
            }
            if (!open.contains(product.id())) {
                throw new InvalidPaymentException("The member has no open account in " + product.name());
            }
        }
        return split;
    }

    // ---- queries ------------------------------------------------------------------------------

    @Override
    public PaymentView payment(UUID chamaId, UUID paymentId) {
        ContributionPayment payment = repository.payment(chamaId, paymentId).orElseThrow(PaymentNotFoundException::new);
        List<Allocation> split = repository.allocations(chamaId, List.of(paymentId)).getOrDefault(paymentId, List.of());
        Catalog catalog = catalog(chamaId);
        Optional<MemberSummary> member = members.seatedMember(chamaId, payment.memberUserId());
        if (member.isPresent()) {
            return view(payment, nameOf(member.get()), member.get().memberNumber(), split, catalog);
        }
        return view(payment, users.findById(payment.memberUserId()).map(UserSummary::fullName).orElse(null), null,
                split, catalog);
    }

    @Override
    public Page<PaymentView> payments(UUID chamaId, UUID memberUserId, LocalDate from, LocalDate to,
                                      ContributionPayment.Status status, Pageable pageable) {
        Page<ContributionPayment> page = repository.payments(chamaId, memberUserId, from, to, status, pageable);
        return views(chamaId, page);
    }

    @Override
    public List<PeriodSummaryView> periods(UUID chamaId) {
        periods.bringUpToDate(chamaId, zone);
        Book book = book(chamaId);
        return book.periods.reversed().stream().map(p -> summary(p, book)).toList();
    }

    @Override
    public PeriodDetailView period(UUID chamaId, UUID periodId) {
        periods.bringUpToDate(chamaId, zone);
        ContributionPeriod period = repository.period(chamaId, periodId).orElseThrow(PeriodNotFoundException::new);
        Book book = book(chamaId);
        Map<UUID, MemberSummary> seated = seatedById(chamaId);
        List<MemberPeriodView> rows = new ArrayList<>();
        book.standings.forEach((memberId, standing) -> standing.periods().stream()
                .filter(ps -> ps.period().id().equals(period.id()))
                .findFirst()
                .ifPresent(ps -> {
                    MemberSummary m = seated.get(memberId);
                    rows.add(new MemberPeriodView(memberId, m == null ? null : nameOf(m),
                            m == null ? null : m.memberNumber(), ps.amountDueMinor(), ps.paidMinor(), ps.status(),
                            ps.late()));
                }));
        rows.sort(Comparator.comparing(MemberPeriodView::memberNumber, Comparator.nullsLast(Comparator.naturalOrder())));
        return new PeriodDetailView(summary(period, book), rows);
    }

    @Override
    public List<StandingView> standings(UUID chamaId, boolean onlyInArrears) {
        periods.bringUpToDate(chamaId, zone);
        Book book = book(chamaId);
        String currency = rules(chamaId).currency();
        return members.seatedMembers(chamaId).stream()
                .map(m -> standingView(m, book.perProduct.getOrDefault(m.userId(), Map.of()), currency, book.catalog))
                .filter(s -> !onlyInArrears || s.arrearsMinor() > 0)
                .sorted(Comparator.comparingLong(StandingView::arrearsMinor).reversed()
                        .thenComparing(StandingView::memberNumber, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    @Override
    public MyContributionsView mine(UUID chamaId, UUID memberUserId) {
        periods.bringUpToDate(chamaId, zone);
        ContributionRules rules = rules(chamaId);
        Catalog catalog = catalog(chamaId);
        MemberSummary member = members.seatedMember(chamaId, memberUserId).orElseThrow(NotASeatedMemberException::new);
        Map<UUID, MemberStanding> perProduct = standingsOf(chamaId, memberUserId, rules);
        MemberStanding standing = MemberStanding.combine(List.copyOf(perProduct.values()));
        List<MyPeriodView> periodViews = standing.periods().reversed().stream()
                .map(ps -> new MyPeriodView(view(ps.period(), catalog), ps.amountDueMinor(), ps.paidMinor(), ps.status(),
                        ps.due(), ps.late()))
                .toList();
        List<PaymentView> recent = views(chamaId, repository.payments(chamaId, memberUserId, null, null, null,
                PageRequest.of(0, RECENT_PAYMENTS))).getContent();
        return new MyContributionsView(standingView(member, perProduct, rules.currency(), catalog), periodViews, recent);
    }

    // ---- LateContributions (for the penalty module) --------------------------------------------

    @Override
    public List<LateContribution> late(UUID chamaId) {
        periods.bringUpToDate(chamaId, zone);
        Book book = book(chamaId);
        String currency = rules(chamaId).currency();
        Set<UUID> seated = seatedById(chamaId).keySet();
        List<LateContribution> late = new ArrayList<>();
        book.standings.forEach((memberId, standing) -> {
            if (!seated.contains(memberId)) {
                return;
            }
            standing.periods().stream().filter(PeriodStanding::late).forEach(ps -> late.add(new LateContribution(
                    memberId, ps.period().id(), ps.period().key(), ps.period().dueDate(), ps.outstandingMinor(), currency)));
        });
        late.sort(Comparator.comparing(LateContribution::dueDate).thenComparing(LateContribution::memberUserId));
        return late;
    }

    // ---- ContributionReports (for the report module) -------------------------------------------

    @Override
    public ContributionMatrix matrix(UUID chamaId) {
        periods.bringUpToDate(chamaId, zone);
        Book book = book(chamaId);
        String currency = rules(chamaId).currency();
        List<PeriodColumn> columns = book.periods.stream().map(p -> {
            PeriodSummaryView s = summary(p, book);
            return new PeriodColumn(p.id(), p.key(), p.dueDate(), s.due(), s.expectedMinor(), s.collectedMinor(),
                    s.members(), s.membersPaid(), s.membersLate());
        }).toList();
        List<MemberRow> rows = members.seatedMembers(chamaId).stream().map(m -> {
            MemberStanding standing = book.standings.getOrDefault(m.userId(), MemberStanding.combine(List.of()));
            Map<UUID, PeriodStanding> byPeriod = standing.periods().stream()
                    .collect(Collectors.toMap(ps -> ps.period().id(), Function.identity()));
            List<Cell> cells = book.periods.stream().map(p -> {
                PeriodStanding ps = byPeriod.get(p.id());
                return ps == null ? null
                        : new Cell(ps.amountDueMinor(), ps.paidMinor(), ps.status().name(), ps.late());
            }).toList();
            return new MemberRow(m.userId(), snapshot(standing), cells);
        }).toList();
        return new ContributionMatrix(currency, columns, rows);
    }

    @Override
    public StandingSnapshot standing(UUID chamaId, UUID memberUserId) {
        periods.bringUpToDate(chamaId, zone);
        return snapshot(MemberStanding.combine(List.copyOf(standingsOf(chamaId, memberUserId, rules(chamaId)).values())));
    }

    private static StandingSnapshot snapshot(MemberStanding s) {
        PeriodStanding latest = s.periods().isEmpty() ? null : s.periods().getLast();
        return new StandingSnapshot(s.totalPaidMinor(), s.dueToDateMinor(), s.arrearsMinor(), s.advanceMinor(),
                s.latePeriods(), latest == null ? null : latest.period().key(),
                latest == null ? null : latest.status().name());
    }

    // ---- standings ----------------------------------------------------------------------------

    /** The chama's savings products and what each asked in each period, loaded once. */
    private record Catalog(Map<UUID, SavingsProductSummary> products, SavingsProductSummary defaultProduct,
                           Map<UUID, Map<UUID, Long>> asked) {

        SavingsProductSummary product(UUID id) {
            return products.get(id);
        }
    }

    private Catalog catalog(UUID chamaId) {
        Map<UUID, SavingsProductSummary> products = savingsProducts.products(chamaId).stream()
                .collect(Collectors.toMap(SavingsProductSummary::id, Function.identity()));
        return new Catalog(products, savingsProducts.defaultProduct(chamaId), repository.periodProducts(chamaId));
    }

    /**
     * Everything needed to work out every member's standing, loaded once: per member and product,
     * and combined per member.
     */
    private record Book(List<ContributionPeriod> periods,
                        Map<UUID, Map<UUID, MemberStanding>> perProduct,
                        Map<UUID, MemberStanding> standings,
                        Catalog catalog) {
    }

    private Book book(UUID chamaId) {
        ContributionRules rules = rules(chamaId);
        List<ContributionPeriod> all = repository.periods(chamaId);
        Map<UUID, ContributionPeriod> byId = all.stream().collect(Collectors.toMap(ContributionPeriod::id, Function.identity()));
        Map<UUID, Map<UUID, List<Due>>> dues = new HashMap<>();
        for (DueRow row : repository.dues(chamaId)) {
            dues.computeIfAbsent(row.memberUserId(), k -> new HashMap<>())
                    .computeIfAbsent(row.productId(), k -> new ArrayList<>())
                    .add(new Due(byId.get(row.periodId()), row.amountMinor()));
        }
        Map<UUID, Map<UUID, Long>> paid = repository.paidTotals(chamaId);
        Set<UUID> memberIds = new HashSet<>(dues.keySet());
        memberIds.addAll(paid.keySet());
        Map<UUID, Map<UUID, MemberStanding>> perProduct = new HashMap<>();
        Map<UUID, MemberStanding> standings = new HashMap<>();
        for (UUID memberId : memberIds) {
            Map<UUID, MemberStanding> mine = standings(dues.getOrDefault(memberId, Map.of()),
                    paid.getOrDefault(memberId, Map.of()), rules);
            perProduct.put(memberId, mine);
            standings.put(memberId, MemberStanding.combine(List.copyOf(mine.values())));
        }
        return new Book(all, perProduct, standings, catalog(chamaId));
    }

    private Map<UUID, MemberStanding> standingsOf(UUID chamaId, UUID memberUserId, ContributionRules rules) {
        Map<UUID, ContributionPeriod> byId = repository.periods(chamaId).stream()
                .collect(Collectors.toMap(ContributionPeriod::id, Function.identity()));
        Map<UUID, List<Due>> dues = new HashMap<>();
        for (DueRow row : repository.duesOf(chamaId, memberUserId)) {
            dues.computeIfAbsent(row.productId(), k -> new ArrayList<>()).add(new Due(byId.get(row.periodId()), row.amountMinor()));
        }
        return standings(dues, repository.paidTotals(chamaId, memberUserId), rules);
    }

    /** One standing per product the member owes or paid into. */
    private Map<UUID, MemberStanding> standings(Map<UUID, List<Due>> dues, Map<UUID, Long> paid, ContributionRules rules) {
        Set<UUID> productIds = new HashSet<>(dues.keySet());
        productIds.addAll(paid.keySet());
        LocalDate today = today();
        Map<UUID, MemberStanding> result = new HashMap<>();
        for (UUID productId : productIds) {
            result.put(productId, MemberStanding.of(dues.getOrDefault(productId, List.of()),
                    paid.getOrDefault(productId, 0L), today, rules.lateGraceDays()));
        }
        return result;
    }

    private PeriodSummaryView summary(ContributionPeriod period, Book book) {
        long expected = 0;
        long collected = 0;
        int count = 0;
        int paid = 0;
        int late = 0;
        for (MemberStanding standing : book.standings.values()) {
            for (PeriodStanding ps : standing.periods()) {
                if (!ps.period().id().equals(period.id())) {
                    continue;
                }
                count++;
                expected += ps.amountDueMinor();
                collected += ps.paidMinor();
                if (ps.status() == MemberStanding.Status.PAID) {
                    paid++;
                }
                if (ps.late()) {
                    late++;
                }
            }
        }
        return new PeriodSummaryView(view(period, book.catalog), period.isDueBy(today()), expected, collected, count,
                paid, late);
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static LedgerAccountKey accountFor(PaymentMethod method) {
        return switch (method) {
            case CASH -> LedgerAccountKey.CASH;
            case BANK -> LedgerAccountKey.BANK;
            case MPESA -> LedgerAccountKey.MOBILE_MONEY;
        };
    }

    private ContributionRules rules(UUID chamaId) {
        return chamas.contributionRules(chamaId).orElseThrow();
    }

    private Map<UUID, MemberSummary> seatedById(UUID chamaId) {
        return members.seatedMembers(chamaId).stream().collect(Collectors.toMap(MemberSummary::userId, Function.identity()));
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(zone));
    }

    private static String nameOf(MemberSummary member) {
        String name = member.fullName().trim();
        return name.isEmpty() ? member.email() : name;
    }

    private static StandingView standingView(MemberSummary member, Map<UUID, MemberStanding> perProduct,
                                             String currency, Catalog catalog) {
        MemberStanding s = MemberStanding.combine(List.copyOf(perProduct.values()));
        List<ProductStandingView> products = perProduct.entrySet().stream()
                .map(e -> {
                    SavingsProductSummary p = catalog.product(e.getKey());
                    MemberStanding ps = e.getValue();
                    return new ProductStandingView(e.getKey(), p == null ? null : p.code(), p == null ? null : p.name(),
                            ps.totalPaidMinor(), ps.dueToDateMinor(), ps.arrearsMinor(), ps.advanceMinor());
                })
                .sorted(Comparator.comparingInt((ProductStandingView v) -> {
                    SavingsProductSummary p = catalog.product(v.productId());
                    return p == null ? Integer.MAX_VALUE : p.allocationPriority();
                }).thenComparing(ProductStandingView::productCode, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        return new StandingView(member.userId(), nameOf(member), member.memberNumber(), currency, s.totalPaidMinor(),
                s.dueToDateMinor(), s.arrearsMinor(), s.advanceMinor(), s.balanceMinor(), s.latePeriods(), products);
    }

    private static PeriodView view(ContributionPeriod p, Catalog catalog) {
        List<PeriodProductView> products = catalog.asked().getOrDefault(p.id(), Map.of()).entrySet().stream()
                .map(e -> {
                    SavingsProductSummary product = catalog.product(e.getKey());
                    return new PeriodProductView(e.getKey(), product == null ? null : product.code(),
                            product == null ? null : product.name(), e.getValue());
                })
                .sorted(Comparator.comparing(PeriodProductView::productCode, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        return new PeriodView(p.id(), p.key(), p.frequency(), p.startDate(), p.endDate(), p.dueDate(),
                p.amountDueMinor(), p.currency(), products);
    }

    /** A page of payments with member names and allocations, loaded in bulk. */
    private Page<PaymentView> views(UUID chamaId, Page<ContributionPayment> page) {
        Map<UUID, MemberSummary> seated = seatedById(chamaId);
        Map<UUID, UserSummary> others = users.findByIds(page.stream().map(ContributionPayment::memberUserId)
                .filter(id -> !seated.containsKey(id)).collect(Collectors.toSet()));
        Map<UUID, List<Allocation>> allocations = repository.allocations(chamaId,
                page.stream().map(ContributionPayment::id).toList());
        Catalog catalog = catalog(chamaId);
        return page.map(p -> {
            List<Allocation> split = allocations.getOrDefault(p.id(), List.of());
            MemberSummary m = seated.get(p.memberUserId());
            if (m != null) {
                return view(p, nameOf(m), m.memberNumber(), split, catalog);
            }
            UserSummary user = others.get(p.memberUserId());
            return view(p, user == null ? null : user.fullName(), null, split, catalog);
        });
    }

    private static PaymentView view(ContributionPayment p, String name, Integer number, List<Allocation> split,
                                    Catalog catalog) {
        List<AllocationView> allocations = split.stream().map(a -> {
            SavingsProductSummary product = catalog.product(a.productId());
            return new AllocationView(a.productId(), product == null ? null : product.code(),
                    product == null ? null : product.name(), a.amountMinor());
        }).toList();
        return new PaymentView(p.id(), p.memberUserId(), name, number, p.amountMinor(), p.currency(), p.method(),
                p.reference(), p.paidOn(), p.notes(), p.status(), p.ledgerEntryId(), p.recordedBy(), p.recordedAt(),
                p.reversedBy(), p.reversedAt(), p.reversalReason(), allocations);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LocalDate> earliestDueDate(UUID chamaId) {
        return repository.periods(chamaId).stream().map(ContributionPeriod::dueDate).min(LocalDate::compareTo);
    }
}
