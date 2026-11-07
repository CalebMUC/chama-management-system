package ke.chama.modules.contribution.application.service;

import ke.chama.modules.contribution.api.LateContributions;
import ke.chama.modules.audit.api.AuditEntry;
import ke.chama.modules.audit.api.AuditTrail;
import ke.chama.modules.auth.api.UserDirectory;
import ke.chama.modules.auth.api.UserSummary;
import ke.chama.modules.chama.api.ChamaDirectory;
import ke.chama.modules.chama.api.ChamaSummary;
import ke.chama.modules.chama.api.ContributionRules;
import ke.chama.modules.contribution.application.port.in.ContributionUseCases;
import ke.chama.modules.contribution.application.port.in.ContributionViews.MemberPeriodView;
import ke.chama.modules.contribution.application.port.in.ContributionViews.MyContributionsView;
import ke.chama.modules.contribution.application.port.in.ContributionViews.MyPeriodView;
import ke.chama.modules.contribution.application.port.in.ContributionViews.PaymentView;
import ke.chama.modules.contribution.application.port.in.ContributionViews.PeriodDetailView;
import ke.chama.modules.contribution.application.port.in.ContributionViews.PeriodSummaryView;
import ke.chama.modules.contribution.application.port.in.ContributionViews.PeriodView;
import ke.chama.modules.contribution.application.port.in.ContributionViews.StandingView;
import ke.chama.modules.contribution.application.port.out.ContributionRepository;
import ke.chama.modules.contribution.application.port.out.ContributionRepository.DueRow;
import ke.chama.modules.contribution.domain.event.ContributionRecorded;
import ke.chama.modules.contribution.domain.exception.ContributionExceptions.DuplicateReferenceException;
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
import ke.chama.modules.contribution.domain.model.PaymentMethod;
import ke.chama.modules.member.api.MemberDirectory;
import ke.chama.modules.member.api.MemberSummary;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Contributions: recording and reversing payments (each posted to the ledger), and where every
 * member stands. Write transactions throughout, because every operation first brings the
 * chama's periods and dues up to date ({@link PeriodOpener}).
 */
@Service
@Transactional
public class ContributionService implements ContributionUseCases, LateContributions {

    static final String SOURCE_TYPE = "CONTRIBUTION";
    static final String AUDIT_TARGET = "CONTRIBUTION";
    static final int RECENT_PAYMENTS = 10;

    private final ContributionRepository repository;
    private final PeriodOpener periods;
    private final MemberDirectory members;
    private final UserDirectory users;
    private final ChamaDirectory chamas;
    private final Ledger ledger;
    private final AuditTrail auditTrail;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final ZoneId zone;

    public ContributionService(ContributionRepository repository, PeriodOpener periods, MemberDirectory members,
                               UserDirectory users, ChamaDirectory chamas, Ledger ledger, AuditTrail auditTrail,
                               ApplicationEventPublisher events, Clock clock,
                               @Value("${chama.time-zone:Africa/Nairobi}") String timeZone) {
        this.repository = repository;
        this.periods = periods;
        this.members = members;
        this.users = users;
        this.chamas = chamas;
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

        ContributionPayment payment = ContributionPayment.record(chamaId, member.userId(), request.amountMinor(),
                rules.currency(), request.method(), request.reference(), request.paidOn(), request.notes(),
                actorUserId, today(), clock.instant());
        if (payment.reference() != null
                && repository.recordedReferenceExists(chamaId, payment.method(), payment.reference())) {
            throw new DuplicateReferenceException(payment.reference());
        }

        Money amount = Money.of(payment.amountMinor(), Currency.getInstance(payment.currency()));
        UUID entryId = ledger.post(new PostingRequest(chamaId, payment.paidOn(),
                "Contribution from " + nameOf(member) + " (member #" + member.memberNumber() + ")",
                payment.reference(), SOURCE_TYPE, payment.id().toString(), actorUserId, List.of(
                PostingLine.debit(accountFor(payment.method()), amount),
                PostingLine.credit(LedgerAccountKey.MEMBER_SAVINGS, amount).forMember(member.userId()))));
        payment.postedAs(entryId);
        repository.insertPayment(payment);

        auditTrail.record(AuditEntry.of(chamaId, actorUserId, "CONTRIBUTION_RECORDED", AUDIT_TARGET, payment.id())
                .with(Map.of("memberUserId", member.userId().toString(), "amountMinor", payment.amountMinor(),
                        "method", payment.method().name(), "reference", payment.reference() == null ? "" : payment.reference())));

        MemberStanding standing = standingOf(chamaId, member.userId(), rules);
        long savings = ledger.memberBalance(chamaId, member.userId(), LedgerAccountKey.MEMBER_SAVINGS).minorUnits();
        String chamaName = chamas.find(chamaId).map(ChamaSummary::name).orElse("your chama");
        events.publishEvent(new ContributionRecorded(UUID.randomUUID(), clock.instant(), chamaId, chamaName,
                payment.id(), member.userId(), member.email(), member.firstName(), payment.amountMinor(),
                payment.currency(), payment.method().name(), payment.reference(), payment.paidOn(), savings,
                standing.arrearsMinor()));
        return view(payment, member);
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
        return view(payment, members.seatedMember(chamaId, payment.memberUserId()).orElse(null));
    }

    // ---- queries ------------------------------------------------------------------------------

    @Override
    public PaymentView payment(UUID chamaId, UUID paymentId) {
        ContributionPayment payment = repository.payment(chamaId, paymentId).orElseThrow(PaymentNotFoundException::new);
        return view(payment, members.seatedMember(chamaId, payment.memberUserId()).orElse(null));
    }

    @Override
    public Page<PaymentView> payments(UUID chamaId, UUID memberUserId, LocalDate from, LocalDate to,
                                      ContributionPayment.Status status, Pageable pageable) {
        Page<ContributionPayment> page = repository.payments(chamaId, memberUserId, from, to, status, pageable);
        Map<UUID, MemberSummary> seated = seatedById(chamaId);
        Map<UUID, UserSummary> others = users.findByIds(page.stream().map(ContributionPayment::memberUserId)
                .filter(id -> !seated.containsKey(id)).collect(Collectors.toSet()));
        return page.map(p -> seated.containsKey(p.memberUserId())
                ? view(p, seated.get(p.memberUserId()))
                : view(p, others.get(p.memberUserId())));
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
                .map(m -> standingView(m, book.standings.getOrDefault(m.userId(), empty(book, m.userId())), currency))
                .filter(s -> !onlyInArrears || s.arrearsMinor() > 0)
                .sorted(Comparator.comparingLong(StandingView::arrearsMinor).reversed()
                        .thenComparing(StandingView::memberNumber, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    @Override
    public MyContributionsView mine(UUID chamaId, UUID memberUserId) {
        periods.bringUpToDate(chamaId, zone);
        ContributionRules rules = rules(chamaId);
        MemberSummary member = members.seatedMember(chamaId, memberUserId).orElseThrow(NotASeatedMemberException::new);
        MemberStanding standing = standingOf(chamaId, memberUserId, rules);
        List<MyPeriodView> periodViews = standing.periods().reversed().stream()
                .map(ps -> new MyPeriodView(view(ps.period()), ps.amountDueMinor(), ps.paidMinor(), ps.status(),
                        ps.due(), ps.late()))
                .toList();
        List<PaymentView> recent = repository.payments(chamaId, memberUserId, null, null, null,
                PageRequest.of(0, RECENT_PAYMENTS)).map(p -> view(p, member)).getContent();
        return new MyContributionsView(standingView(member, standing, rules.currency()), periodViews, recent);
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

    // ---- standings ----------------------------------------------------------------------------

    /** Everything needed to work out every member's standing, loaded once. */
    private record Book(List<ContributionPeriod> periods, Map<UUID, MemberStanding> standings) {
    }

    private Book book(UUID chamaId) {
        ContributionRules rules = rules(chamaId);
        List<ContributionPeriod> all = repository.periods(chamaId);
        Map<UUID, ContributionPeriod> byId = all.stream().collect(Collectors.toMap(ContributionPeriod::id, Function.identity()));
        Map<UUID, List<Due>> dues = new HashMap<>();
        for (DueRow row : repository.dues(chamaId)) {
            dues.computeIfAbsent(row.memberUserId(), k -> new ArrayList<>()).add(new Due(byId.get(row.periodId()), row.amountMinor()));
        }
        Map<UUID, Long> paid = repository.paidTotals(chamaId);
        Set<UUID> memberIds = new java.util.HashSet<>(dues.keySet());
        memberIds.addAll(paid.keySet());
        LocalDate today = today();
        Map<UUID, MemberStanding> standings = new HashMap<>();
        for (UUID memberId : memberIds) {
            standings.put(memberId, MemberStanding.of(dues.getOrDefault(memberId, List.of()),
                    paid.getOrDefault(memberId, 0L), today, rules.lateGraceDays()));
        }
        return new Book(all, standings);
    }

    private MemberStanding standingOf(UUID chamaId, UUID memberUserId, ContributionRules rules) {
        Map<UUID, ContributionPeriod> byId = repository.periods(chamaId).stream()
                .collect(Collectors.toMap(ContributionPeriod::id, Function.identity()));
        List<Due> dues = repository.duesOf(chamaId, memberUserId).stream()
                .map(row -> new Due(byId.get(row.periodId()), row.amountMinor())).toList();
        return MemberStanding.of(dues, repository.paidTotal(chamaId, memberUserId), today(), rules.lateGraceDays());
    }

    private MemberStanding empty(Book book, UUID memberId) {
        return MemberStanding.of(List.of(), 0, today(), 0);
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
        return new PeriodSummaryView(view(period), period.isDueBy(today()), expected, collected, count, paid, late);
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

    private static StandingView standingView(MemberSummary member, MemberStanding s, String currency) {
        return new StandingView(member.userId(), nameOf(member), member.memberNumber(), currency, s.totalPaidMinor(),
                s.dueToDateMinor(), s.arrearsMinor(), s.advanceMinor(), s.balanceMinor(), s.latePeriods());
    }

    private static PeriodView view(ContributionPeriod p) {
        return new PeriodView(p.id(), p.key(), p.frequency(), p.startDate(), p.endDate(), p.dueDate(),
                p.amountDueMinor(), p.currency());
    }

    private static PaymentView view(ContributionPayment p, MemberSummary member) {
        return view(p, member == null ? null : nameOf(member), member == null ? null : member.memberNumber());
    }

    private static PaymentView view(ContributionPayment p, UserSummary user) {
        return view(p, user == null ? null : user.fullName(), null);
    }

    private static PaymentView view(ContributionPayment p, String name, Integer number) {
        return new PaymentView(p.id(), p.memberUserId(), name, number, p.amountMinor(), p.currency(), p.method(),
                p.reference(), p.paidOn(), p.notes(), p.status(), p.ledgerEntryId(), p.recordedBy(), p.recordedAt(),
                p.reversedBy(), p.reversedAt(), p.reversalReason());
    }
}
