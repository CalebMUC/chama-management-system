package ke.chama.modules.penalty.application.service;

import ke.chama.modules.audit.api.AuditEntry;
import ke.chama.modules.audit.api.AuditTrail;
import ke.chama.modules.auth.api.UserDirectory;
import ke.chama.modules.auth.api.UserSummary;
import ke.chama.modules.chama.api.ChamaDirectory;
import ke.chama.modules.chama.api.ChamaSummary;
import ke.chama.modules.chama.api.ContributionRules;
import ke.chama.modules.contribution.api.LateContributions;
import ke.chama.modules.contribution.api.LateContributions.LateContribution;
import ke.chama.modules.member.api.MemberDirectory;
import ke.chama.modules.member.api.MemberSummary;
import ke.chama.modules.penalty.application.port.in.PenaltyUseCases;
import ke.chama.modules.penalty.application.port.out.PenaltyRepository;
import ke.chama.modules.penalty.domain.event.PenaltyAssessed;
import ke.chama.modules.penalty.domain.exception.PenaltyExceptions.DuplicateReferenceException;
import ke.chama.modules.penalty.domain.exception.PenaltyExceptions.NotASeatedMemberException;
import ke.chama.modules.penalty.domain.exception.PenaltyExceptions.PenaltyNotFoundException;
import ke.chama.modules.penalty.domain.exception.PenaltyExceptions.SelfHandlingNotAllowedException;
import ke.chama.modules.penalty.domain.model.Penalty;
import ke.chama.modules.penalty.domain.model.PenaltyPayment;
import ke.chama.modules.transaction.api.Ledger;
import ke.chama.modules.transaction.api.LedgerAccountKey;
import ke.chama.modules.transaction.api.PostingRequest;
import ke.chama.modules.transaction.api.PostingRequest.PostingLine;
import ke.chama.shared.domain.Money;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Penalties: automatic late-contribution penalties, fines, payments and waivers. Every step is
 * posted to the ledger against the member's "Penalties receivable" and audit-logged.
 */
@Service
@Transactional
public class PenaltyService implements PenaltyUseCases {

    static final String SOURCE_ASSESSMENT = "PENALTY";
    static final String SOURCE_PAYMENT = "PENALTY_PAYMENT";
    static final String SOURCE_WAIVER = "PENALTY_WAIVER";
    static final String AUDIT_TARGET = "PENALTY";

    private final PenaltyRepository repository;
    private final LateContributions lateContributions;
    private final MemberDirectory members;
    private final UserDirectory users;
    private final ChamaDirectory chamas;
    private final Ledger ledger;
    private final AuditTrail auditTrail;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final ZoneId zone;

    public PenaltyService(PenaltyRepository repository, LateContributions lateContributions, MemberDirectory members,
                          UserDirectory users, ChamaDirectory chamas, Ledger ledger, AuditTrail auditTrail,
                          ApplicationEventPublisher events, Clock clock,
                          @Value("${chama.time-zone:Africa/Nairobi}") String timeZone) {
        this.repository = repository;
        this.lateContributions = lateContributions;
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
    public List<PenaltyView> assessLateContributions(UUID chamaId) {
        ContributionRules rules = chamas.contributionRules(chamaId).orElseThrow();
        if (rules.latePenaltyBasisPoints() <= 0) {
            return List.of();
        }
        Set<String> assessed = repository.assessedSourceKeys(chamaId);
        Map<UUID, MemberSummary> seated = seatedById(chamaId);
        List<PenaltyView> created = new ArrayList<>();
        for (LateContribution late : lateContributions.late(chamaId)) {
            String key = late.memberUserId() + "/" + Penalty.lateContributionKey(late.periodId());
            if (assessed.contains(key)) {
                continue;
            }
            Penalty.forLateContribution(chamaId, late.memberUserId(), late.periodId(), late.periodKey(),
                    late.outstandingMinor(), late.currency(), rules.latePenaltyBasisPoints(), today(), clock.instant())
                    .ifPresent(penalty -> {
                        assess(penalty, null);
                        created.add(view(penalty, seated.get(penalty.memberUserId())));
                    });
        }
        return created;
    }

    @Override
    public PenaltyView fine(UUID chamaId, UUID actorUserId, UUID memberUserId, long amountMinor, String reason) {
        MemberSummary member = members.seatedMember(chamaId, memberUserId).orElseThrow(NotASeatedMemberException::new);
        if (member.userId().equals(actorUserId)) {
            throw new SelfHandlingNotAllowedException();
        }
        String currency = chamas.contributionRules(chamaId).orElseThrow().currency();
        Penalty penalty = Penalty.fine(chamaId, member.userId(), amountMinor, currency, reason, actorUserId, today(),
                clock.instant());
        assess(penalty, actorUserId);
        return view(penalty, member);
    }

    @Override
    public PenaltyView recordPayment(UUID chamaId, UUID actorUserId, UUID penaltyId, PaymentRequest request) {
        Penalty penalty = load(chamaId, penaltyId);
        if (penalty.memberUserId().equals(actorUserId)) {
            throw new SelfHandlingNotAllowedException();
        }
        PenaltyPayment payment = PenaltyPayment.record(penalty, request.amountMinor(), request.method(),
                request.reference(), request.paidOn(), actorUserId, today(), clock.instant());
        if (payment.reference() != null && repository.referenceExists(chamaId, payment.method(), payment.reference())) {
            throw new DuplicateReferenceException(payment.reference());
        }
        penalty.pay(payment.amountMinor());

        Money amount = money(payment.amountMinor(), penalty.currency());
        UUID entryId = ledger.post(new PostingRequest(chamaId, payment.paidOn(), "Penalty payment: " + penalty.description(),
                payment.reference(), SOURCE_PAYMENT, payment.id().toString(), actorUserId, List.of(
                PostingLine.debit(accountFor(payment.method()), amount),
                PostingLine.credit(LedgerAccountKey.PENALTIES_RECEIVABLE, amount).forMember(penalty.memberUserId()))));
        repository.insertPayment(payment.postedAs(entryId));
        save(penalty);
        auditTrail.record(AuditEntry.of(chamaId, actorUserId, "PENALTY_PAID", AUDIT_TARGET, penaltyId)
                .with(Map.of("amountMinor", payment.amountMinor(), "method", payment.method(),
                        "outstandingMinor", penalty.outstandingMinor())));
        return view(penalty, members.seatedMember(chamaId, penalty.memberUserId()).orElse(null));
    }

    @Override
    public PenaltyView waive(UUID chamaId, UUID actorUserId, UUID penaltyId, String reason) {
        Penalty penalty = load(chamaId, penaltyId);
        if (penalty.memberUserId().equals(actorUserId)) {
            throw new SelfHandlingNotAllowedException();
        }
        long waived = penalty.waive(actorUserId, reason, clock.instant());
        Money amount = money(waived, penalty.currency());
        UUID entryId = ledger.post(new PostingRequest(chamaId, null, "Penalty waived: " + penalty.description(),
                null, SOURCE_WAIVER, penalty.id().toString(), actorUserId, List.of(
                PostingLine.debit(LedgerAccountKey.PENALTY_INCOME, amount),
                PostingLine.credit(LedgerAccountKey.PENALTIES_RECEIVABLE, amount).forMember(penalty.memberUserId()))));
        penalty.waiverPostedAs(entryId);
        save(penalty);
        auditTrail.record(AuditEntry.of(chamaId, actorUserId, "PENALTY_WAIVED", AUDIT_TARGET, penaltyId)
                .with(Map.of("waivedMinor", waived, "reason", penalty.waiverReason())));
        return view(penalty, members.seatedMember(chamaId, penalty.memberUserId()).orElse(null));
    }

    // ---- queries ------------------------------------------------------------------------------

    @Override
    public PenaltyDetailView penalty(UUID chamaId, UUID penaltyId) {
        Penalty penalty = load(chamaId, penaltyId);
        List<PenaltyPaymentView> payments = repository.payments(chamaId, penaltyId).stream()
                .map(p -> new PenaltyPaymentView(p.id(), p.amountMinor(), p.method(), p.reference(), p.paidOn(),
                        p.ledgerEntryId(), p.recordedBy(), p.recordedAt()))
                .toList();
        return new PenaltyDetailView(view(penalty, members.seatedMember(chamaId, penalty.memberUserId()).orElse(null)),
                payments);
    }

    @Override
    public Page<PenaltyView> penalties(UUID chamaId, UUID memberUserId, Penalty.Status status, Pageable pageable) {
        assessLateContributions(chamaId);
        Page<Penalty> page = repository.penalties(chamaId, memberUserId, status, pageable);
        Map<UUID, MemberSummary> seated = seatedById(chamaId);
        return page.map(p -> view(p, seated.get(p.memberUserId())));
    }

    @Override
    public MyPenaltiesView mine(UUID chamaId, UUID memberUserId) {
        assessLateContributions(chamaId);
        MemberSummary member = members.seatedMember(chamaId, memberUserId).orElseThrow(NotASeatedMemberException::new);
        List<PenaltyView> penalties = repository.penaltiesOf(chamaId, memberUserId).stream()
                .map(p -> view(p, member)).toList();
        long outstanding = penalties.stream().mapToLong(PenaltyView::outstandingMinor).sum();
        String currency = chamas.contributionRules(chamaId).orElseThrow().currency();
        return new MyPenaltiesView(currency, outstanding, penalties);
    }

    // ---- helpers ------------------------------------------------------------------------------

    private void assess(Penalty penalty, UUID actorUserId) {
        Money amount = money(penalty.amountMinor(), penalty.currency());
        UUID entryId = ledger.post(new PostingRequest(penalty.chamaId(), null, penalty.description(), null,
                SOURCE_ASSESSMENT, penalty.id().toString(), actorUserId, List.of(
                PostingLine.debit(LedgerAccountKey.PENALTIES_RECEIVABLE, amount).forMember(penalty.memberUserId()),
                PostingLine.credit(LedgerAccountKey.PENALTY_INCOME, amount))));
        penalty.postedAs(entryId);
        repository.insert(penalty);
        auditTrail.record(AuditEntry.of(penalty.chamaId(), actorUserId,
                penalty.kind() == Penalty.Kind.FINE ? "PENALTY_FINED" : "PENALTY_ASSESSED", AUDIT_TARGET, penalty.id())
                .with(Map.of("memberUserId", penalty.memberUserId().toString(), "amountMinor", penalty.amountMinor(),
                        "description", penalty.description())));

        UserSummary user = users.findById(penalty.memberUserId()).orElse(null);
        String chamaName = chamas.find(penalty.chamaId()).map(ChamaSummary::name).orElse("your chama");
        if (user != null) {
            events.publishEvent(new PenaltyAssessed(UUID.randomUUID(), clock.instant(), penalty.chamaId(), chamaName,
                    penalty.id(), penalty.memberUserId(), user.email(), user.firstName(), penalty.kind().name(),
                    penalty.description(), penalty.amountMinor(), penalty.currency()));
        }
    }

    private void save(Penalty penalty) {
        if (!repository.update(penalty)) {
            throw new OptimisticLockingFailureException("Penalty " + penalty.id() + " changed concurrently");
        }
    }

    private Penalty load(UUID chamaId, UUID penaltyId) {
        return repository.penalty(chamaId, penaltyId).orElseThrow(PenaltyNotFoundException::new);
    }

    private Map<UUID, MemberSummary> seatedById(UUID chamaId) {
        return members.seatedMembers(chamaId).stream().collect(Collectors.toMap(MemberSummary::userId, Function.identity()));
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

    private LocalDate today() {
        return LocalDate.now(clock.withZone(zone));
    }

    private static PenaltyView view(Penalty p, MemberSummary member) {
        String name = member == null ? null : member.fullName().trim();
        return new PenaltyView(p.id(), p.memberUserId(), name, member == null ? null : member.memberNumber(), p.kind(),
                p.description(), p.amountMinor(), p.paidMinor(), p.waivedMinor(), p.outstandingMinor(), p.currency(),
                p.status(), p.assessedOn(), p.assessedBy(), p.waivedBy(), p.waivedAt(), p.waiverReason());
    }
}
