package ke.chama.modules.memberexit.application.service;

import ke.chama.modules.account.api.MemberAccounts;
import ke.chama.modules.account.api.SavingsProductSummary;
import ke.chama.modules.account.api.SavingsProducts;
import ke.chama.modules.audit.api.AuditEntry;
import ke.chama.modules.audit.api.AuditTrail;
import ke.chama.modules.chama.api.ChamaDirectory;
import ke.chama.modules.chama.api.ChamaSummary;
import ke.chama.modules.loan.api.LoanPortfolio;
import ke.chama.modules.loan.api.LoanPortfolio.HeldGuarantee;
import ke.chama.modules.loan.api.LoanPortfolio.LoanSnapshot;
import ke.chama.modules.loan.api.LoanRepayments;
import ke.chama.modules.member.api.MemberDirectory;
import ke.chama.modules.member.api.MemberExits;
import ke.chama.modules.member.api.MemberSummary;
import ke.chama.modules.memberexit.application.port.in.ExitUseCases;
import ke.chama.modules.memberexit.application.port.out.ExitRepository;
import ke.chama.modules.memberexit.domain.exception.ExitExceptions.DuplicateReferenceException;
import ke.chama.modules.memberexit.domain.exception.ExitExceptions.ExitBlockedException;
import ke.chama.modules.memberexit.domain.exception.ExitExceptions.ExitInProgressException;
import ke.chama.modules.memberexit.domain.exception.ExitExceptions.ExitNotFoundException;
import ke.chama.modules.memberexit.domain.exception.ExitExceptions.InsufficientFundsException;
import ke.chama.modules.memberexit.domain.exception.ExitExceptions.NotASeatedMemberException;
import ke.chama.modules.memberexit.domain.exception.ExitExceptions.SettlementChangedException;
import ke.chama.modules.memberexit.domain.model.MemberExit;
import ke.chama.modules.memberexit.domain.model.Settlement;
import ke.chama.modules.penalty.api.PenaltyPayments;
import ke.chama.modules.penalty.api.PenaltyReports;
import ke.chama.modules.transaction.api.EntrySide;
import ke.chama.modules.transaction.api.Ledger;
import ke.chama.modules.transaction.api.LedgerAccountKey;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Members leaving the chama. The settlement is worked out from the ledger (savings per product),
 * the member's active loans and their outstanding penalties; it is approved as worked out and must
 * still hold, unchanged, when it is paid out.
 * <p>
 * Ledger, when an exit is settled:
 * <ul>
 *   <li>each loan and penalty is cleared from refundable savings through the loan and penalty
 *       modules (Dr the savings account, Cr Loans / Penalties receivable; method SAVINGS);</li>
 *   <li>non-refundable balances: Dr the savings account, Cr 3000 Retained surplus;</li>
 *   <li>the payout: Dr each refundable savings account, Cr cash, bank or M-Pesa.</li>
 * </ul>
 * Then the member's accounts close and the membership ends.
 */
@Service
@Transactional
public class ExitService implements ExitUseCases {

    static final String SOURCE = "MEMBER_EXIT";
    static final String AUDIT_TARGET = "MEMBER_EXIT";

    private final ExitRepository repository;
    private final MemberDirectory members;
    private final MemberExits memberExits;
    private final SavingsProducts products;
    private final MemberAccounts accounts;
    private final LoanPortfolio loans;
    private final LoanRepayments loanRepayments;
    private final PenaltyReports penalties;
    private final PenaltyPayments penaltyPayments;
    private final ChamaDirectory chamas;
    private final Ledger ledger;
    private final AuditTrail auditTrail;
    private final Clock clock;
    private final ZoneId zone;

    public ExitService(ExitRepository repository, MemberDirectory members, MemberExits memberExits,
                       SavingsProducts products, MemberAccounts accounts, LoanPortfolio loans,
                       LoanRepayments loanRepayments, PenaltyReports penalties, PenaltyPayments penaltyPayments,
                       ChamaDirectory chamas, Ledger ledger, AuditTrail auditTrail, Clock clock,
                       @Value("${chama.time-zone:Africa/Nairobi}") String timeZone) {
        this.repository = repository;
        this.members = members;
        this.memberExits = memberExits;
        this.products = products;
        this.accounts = accounts;
        this.loans = loans;
        this.loanRepayments = loanRepayments;
        this.penalties = penalties;
        this.penaltyPayments = penaltyPayments;
        this.chamas = chamas;
        this.ledger = ledger;
        this.auditTrail = auditTrail;
        this.clock = clock;
        this.zone = ZoneId.of(timeZone);
    }

    // ---- commands -------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public ExitPreviewView preview(UUID chamaId, UUID memberUserId) {
        MemberSummary member = seated(chamaId, memberUserId);
        Assessment assessment = assess(chamaId, memberUserId);
        List<String> blockers = new ArrayList<>();
        if (repository.inProgress(chamaId, memberUserId).isPresent()) {
            blockers.add("This member's exit is already in progress");
        }
        blockers.addAll(assessment.blockers());
        return new ExitPreviewView(memberUserId, name(member), member.memberNumber(),
                settlementView(assessment.settlement()), blockers, blockers.isEmpty());
    }

    @Override
    public ExitView request(UUID chamaId, UUID actorUserId, UUID memberUserId, String reason) {
        seated(chamaId, memberUserId);
        if (repository.inProgress(chamaId, memberUserId).isPresent()) {
            throw new ExitInProgressException();
        }
        Assessment assessment = assess(chamaId, memberUserId);
        if (!assessment.blockers().isEmpty()) {
            throw new ExitBlockedException(assessment.blockers());
        }
        MemberExit exit = MemberExit.request(chamaId, memberUserId, actorUserId, reason, assessment.settlement(),
                chamas.approvalQuorum(chamaId).orElse(1), clock.instant());
        repository.insert(exit);
        Settlement s = exit.settlement();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("memberUserId", memberUserId.toString());
        details.put("reason", exit.reason());
        details.put("refundableMinor", s.refundableMinor());
        details.put("forfeitedMinor", s.forfeitedMinor());
        details.put("loansMinor", s.loansMinor());
        details.put("penaltiesMinor", s.penaltiesMinor());
        details.put("payoutMinor", s.payoutMinor());
        audit(exit, actorUserId, "MEMBER_EXIT_REQUESTED", details);
        return view(exit);
    }

    @Override
    public ExitView approve(UUID chamaId, UUID actorUserId, UUID exitId, String note) {
        MemberExit exit = load(chamaId, exitId);
        exit.approve(actorUserId, note, clock.instant());
        save(exit);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("approvals", exit.approvals().size());
        details.put("requiredApprovals", exit.requiredApprovals());
        if (note != null && !note.isBlank()) {
            details.put("note", note.trim());
        }
        audit(exit, actorUserId, "MEMBER_EXIT_APPROVAL_GIVEN", details);
        if (exit.status() == MemberExit.Status.APPROVED) {
            audit(exit, actorUserId, "MEMBER_EXIT_APPROVED", Map.of("payoutMinor", exit.settlement().payoutMinor()));
        }
        return view(exit);
    }

    @Override
    public ExitView reject(UUID chamaId, UUID actorUserId, UUID exitId, String reason) {
        MemberExit exit = load(chamaId, exitId);
        exit.reject(actorUserId, reason, clock.instant());
        save(exit);
        audit(exit, actorUserId, "MEMBER_EXIT_REJECTED", Map.of("reason", exit.rejectionReason()));
        return view(exit);
    }

    @Override
    public ExitView cancel(UUID chamaId, UUID actorUserId, UUID exitId) {
        MemberExit exit = load(chamaId, exitId);
        exit.cancel(actorUserId, clock.instant());
        save(exit);
        audit(exit, actorUserId, "MEMBER_EXIT_CANCELLED", Map.of());
        return view(exit);
    }

    @Override
    public ExitView settle(UUID chamaId, UUID actorUserId, UUID exitId, Payout payout) {
        MemberExit exit = load(chamaId, exitId);
        UUID member = exit.memberUserId();
        exit.settle(actorUserId, payout.method(), payout.reference(), payout.settledOn(), today(), zone);

        // Nothing may have changed since the officials approved the figures.
        Assessment now = assess(chamaId, member);
        if (!now.blockers().isEmpty()) {
            throw new ExitBlockedException(now.blockers());
        }
        Settlement settlement = exit.settlement();
        if (!settlement.sameAs(now.settlement())) {
            throw new SettlementChangedException();
        }
        if (exit.paymentReference() != null
                && repository.referenceExists(chamaId, exit.paymentMethod(), exit.paymentReference())) {
            throw new DuplicateReferenceException(exit.paymentReference());
        }
        Currency currency = Currency.getInstance(settlement.currency());
        LedgerAccountKey source = exit.paymentMethod() == null ? null : accountFor(exit.paymentMethod());
        if (source != null) {
            Money available = ledger.balance(chamaId, source);
            if (available.isLessThan(Money.of(settlement.payoutMinor(), currency))) {
                throw new InsufficientFundsException(accountName(source),
                        formatted(available.minorUnits(), settlement.currency()));
            }
        }

        // 1. What the member owes, cleared from their refundable savings.
        for (Settlement.Offset offset : settlement.offsets()) {
            Settlement.Line debt = offset.debt();
            if (debt.kind() == Settlement.Kind.LOAN) {
                loanRepayments.repayFromSavings(chamaId, actorUserId, debt.sourceId(), offset.draws().stream()
                        .map(d -> new LoanRepayments.SavingsSource(d.accountCode(), d.amountMinor())).toList(),
                        exit.settledOn());
            } else {
                penaltyPayments.payFromSavings(chamaId, actorUserId, debt.sourceId(), offset.draws().stream()
                        .map(d -> new PenaltyPayments.SavingsSource(d.accountCode(), d.amountMinor())).toList(),
                        exit.settledOn());
            }
        }

        // 2. Non-refundable savings to the chama, refundable savings to the member.
        List<PostingLine> lines = new ArrayList<>();
        for (Settlement.Line forfeit : settlement.lines(Settlement.Kind.FORFEIT)) {
            lines.add(memberDebit(forfeit.accountCode(), forfeit.amountMinor(), currency, member)
                    .withMemo("Not refundable: kept by the chama"));
        }
        if (settlement.forfeitedMinor() > 0) {
            lines.add(PostingLine.credit(LedgerAccountKey.RETAINED_SURPLUS, Money.of(settlement.forfeitedMinor(), currency)));
        }
        for (Settlement.Draw draw : settlement.payoutDraws()) {
            lines.add(memberDebit(draw.accountCode(), draw.amountMinor(), currency, member).withMemo("Paid out on exit"));
        }
        if (settlement.payoutMinor() > 0) {
            lines.add(PostingLine.credit(source, Money.of(settlement.payoutMinor(), currency)));
        }
        if (!lines.isEmpty()) {
            exit.settlementPostedAs(ledger.post(new PostingRequest(chamaId, exit.settledOn(),
                    "Exit settlement: " + memberName(chamaId, member), exit.paymentReference(), SOURCE,
                    exit.id().toString(), actorUserId, lines)));
        }

        // 3. Close the member's accounts and end the membership.
        accounts.closeAllForExit(chamaId, member, exit.settledOn(), "Left the chama: " + exit.reason(), actorUserId);
        Instant exitedAt = exit.settledOn().equals(today()) ? clock.instant()
                : exit.settledOn().atStartOfDay(zone).toInstant();
        memberExits.recordExit(chamaId, member, actorUserId, exit.reason(), exitedAt);
        save(exit);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("payoutMinor", settlement.payoutMinor());
        details.put("forfeitedMinor", settlement.forfeitedMinor());
        details.put("loansMinor", settlement.loansMinor());
        details.put("penaltiesMinor", settlement.penaltiesMinor());
        if (exit.paymentMethod() != null) {
            details.put("method", exit.paymentMethod());
        }
        audit(exit, actorUserId, "MEMBER_EXIT_SETTLED", details);
        return view(exit);
    }

    // ---- queries --------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public ExitView exit(UUID chamaId, UUID exitId) {
        return view(load(chamaId, exitId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ExitView> exits(UUID chamaId, MemberExit.Status status, UUID memberUserId, Pageable pageable) {
        Map<UUID, Optional<MemberSummary>> people = new HashMap<>();
        return repository.exits(chamaId, status, memberUserId, pageable).map(e -> view(e, people));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExitView> mine(UUID chamaId, UUID memberUserId) {
        Map<UUID, Optional<MemberSummary>> people = new HashMap<>();
        return repository.exits(chamaId, null, memberUserId, PageRequest.of(0, 50)).stream()
                .map(e -> view(e, people)).toList();
    }

    // ---- the settlement -------------------------------------------------------------------------

    /** The settlement as it stands and whatever stops the member leaving now. */
    private record Assessment(Settlement settlement, List<String> blockers) {
    }

    private Assessment assess(UUID chamaId, UUID memberUserId) {
        String currency = chamas.find(chamaId).map(ChamaSummary::currency).orElseThrow();
        List<String> blockers = new ArrayList<>(memberExits.exitBlockers(chamaId, memberUserId));
        List<Settlement.Line> refunds = new ArrayList<>();
        List<Settlement.Line> forfeits = new ArrayList<>();
        // Debts are cleared from the default product first, then the others in allocation order.
        List<SavingsProductSummary> catalog = products.products(chamaId).stream()
                .sorted(Comparator.comparing((SavingsProductSummary p) -> !p.defaultProduct()))
                .toList();
        for (SavingsProductSummary product : catalog) {
            long balance = ledger.memberBalance(chamaId, memberUserId, product.ledgerAccountCode()).minorUnits();
            if (balance < 0) {
                blockers.add("The member's " + product.name() + " balance is overdrawn by "
                        + formatted(-balance, currency) + ": correct it first");
            } else if (balance > 0) {
                if (product.refundableOnExit()) {
                    refunds.add(new Settlement.Line(Settlement.Kind.REFUND, product.id(), product.ledgerAccountCode(),
                            product.name() + " (refunded)", balance));
                } else {
                    forfeits.add(new Settlement.Line(Settlement.Kind.FORFEIT, product.id(), product.ledgerAccountCode(),
                            product.name() + " (not refundable: kept by the chama)", balance));
                }
            }
        }
        List<Settlement.Line> lines = new ArrayList<>(refunds);
        lines.addAll(forfeits);
        for (LoanSnapshot loan : loans.loansOf(chamaId, memberUserId)) {
            if (loan.active() && loan.outstandingMinor() > 0) {
                lines.add(new Settlement.Line(Settlement.Kind.LOAN, loan.id(), null,
                        "Loan" + (loan.productName() == null ? "" : " (" + loan.productName() + ")") + ": "
                                + loan.purpose(), loan.outstandingMinor()));
            } else if (loan.inProgress()) {
                blockers.add("The member's loan application \"" + loan.purpose()
                        + "\" is still in progress: it must be cancelled or rejected first");
            }
        }
        for (PenaltyReports.OwedItem penalty : penalties.ofMember(chamaId, memberUserId).outstanding()) {
            if (penalty.outstandingMinor() > 0) {
                lines.add(new Settlement.Line(Settlement.Kind.PENALTY, penalty.penaltyId(), null,
                        "Penalty: " + penalty.description(), penalty.outstandingMinor()));
            }
        }
        for (HeldGuarantee guarantee : loans.guaranteesHeldBy(chamaId, memberUserId)) {
            String borrower = memberName(chamaId, guarantee.borrowerUserId());
            if ("ACCEPTED".equals(guarantee.guarantorStatus())) {
                blockers.add("The member guarantees " + borrower + "'s loan \"" + guarantee.purpose() + "\""
                        + (guarantee.outstandingMinor() > 0
                        ? " (" + formatted(guarantee.outstandingMinor(), guarantee.currency()) + " outstanding)" : "")
                        + ": transfer the guarantee to another member first"
                        + (guarantee.replacementRequested() ? " (a new guarantor has been asked and has not answered yet)" : ""));
            } else {
                blockers.add("The member has been asked to guarantee " + borrower + "'s loan \"" + guarantee.purpose()
                        + "\": they must decline (or accept and transfer) the request first");
            }
        }
        Settlement settlement = new Settlement(currency, lines);
        if (settlement.shortfallMinor() > 0) {
            blockers.add("The member owes " + formatted(settlement.shortfallMinor(), currency)
                    + " more than their refundable savings: they must pay it before leaving");
        }
        return new Assessment(settlement, blockers);
    }

    // ---- helpers --------------------------------------------------------------------------------

    private MemberSummary seated(UUID chamaId, UUID memberUserId) {
        return members.seatedMember(chamaId, memberUserId).orElseThrow(NotASeatedMemberException::new);
    }

    private static PostingLine memberDebit(String accountCode, long amountMinor, Currency currency, UUID member) {
        return new PostingLine(null, accountCode, EntrySide.DEBIT, Money.of(amountMinor, currency), member, null);
    }

    private void audit(MemberExit exit, UUID actorUserId, String action, Map<String, ?> details) {
        Map<String, Object> all = new HashMap<>(details);
        all.put("memberUserId", exit.memberUserId().toString());
        auditTrail.record(AuditEntry.of(exit.chamaId(), actorUserId, action, AUDIT_TARGET, exit.id()).with(all));
    }

    private void save(MemberExit exit) {
        if (!repository.update(exit)) {
            throw new OptimisticLockingFailureException("Member exit " + exit.id() + " changed concurrently");
        }
    }

    private MemberExit load(UUID chamaId, UUID exitId) {
        return repository.exit(chamaId, exitId).orElseThrow(ExitNotFoundException::new);
    }

    private String memberName(UUID chamaId, UUID userId) {
        return members.anyMember(chamaId, userId).map(ExitService::name).orElse("a member");
    }

    private static String name(MemberSummary m) {
        String full = m.fullName().trim();
        return full.isEmpty() ? m.email() : full;
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

    private LocalDate today() {
        return LocalDate.now(clock.withZone(zone));
    }

    // ---- views ----------------------------------------------------------------------------------

    private static SettlementView settlementView(Settlement s) {
        return new SettlementView(s.currency(), s.refundableMinor(), s.forfeitedMinor(), s.loansMinor(),
                s.penaltiesMinor(), s.payoutMinor(), s.shortfallMinor(), 0,
                s.lines().stream().map(l -> new LineView(l.kind(), l.sourceId(), l.description(), l.amountMinor())).toList());
    }

    private ExitView view(MemberExit exit) {
        return view(exit, new HashMap<>());
    }

    private ExitView view(MemberExit e, Map<UUID, Optional<MemberSummary>> people) {
        Optional<MemberSummary> member = person(e.chamaId(), e.memberUserId(), people);
        List<ApprovalView> approvals = e.approvals().stream()
                .map(a -> new ApprovalView(a.approverUserId(),
                        person(e.chamaId(), a.approverUserId(), people).map(ExitService::name).orElse(null), a.note(),
                        a.approvedAt()))
                .toList();
        return new ExitView(e.id(), e.memberUserId(), member.map(ExitService::name).orElse(null),
                member.map(MemberSummary::memberNumber).orElse(null), e.reason(), e.status(),
                settlementView(e.settlement()), e.requestedBy(),
                person(e.chamaId(), e.requestedBy(), people).map(ExitService::name).orElse(null), e.createdAt(),
                e.requiredApprovals(), approvals, e.rejectedBy(), e.rejectedAt(), e.rejectionReason(), e.cancelledAt(),
                e.settledOn(), e.settledBy(), e.paymentMethod(), e.paymentReference(), e.settlementEntryId());
    }

    private Optional<MemberSummary> person(UUID chamaId, UUID userId, Map<UUID, Optional<MemberSummary>> people) {
        return people.computeIfAbsent(userId, id -> members.anyMember(chamaId, id));
    }
}
