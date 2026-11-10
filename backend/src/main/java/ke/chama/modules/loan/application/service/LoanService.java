package ke.chama.modules.loan.application.service;

import ke.chama.modules.audit.api.AuditEntry;
import ke.chama.modules.audit.api.AuditTrail;
import ke.chama.modules.chama.api.ChamaDirectory;
import ke.chama.modules.chama.api.ChamaSummary;
import ke.chama.modules.chama.api.LoanRules;
import ke.chama.modules.loan.application.port.in.LoanUseCases;
import ke.chama.modules.loan.application.port.out.LoanRepository;
import ke.chama.modules.loan.domain.event.LoanGuarantorRequested;
import ke.chama.modules.loan.domain.event.LoanStatusChanged;
import ke.chama.modules.loan.domain.exception.LoanExceptions.DuplicateReferenceException;
import ke.chama.modules.loan.domain.exception.LoanExceptions.InsufficientFundsException;
import ke.chama.modules.loan.domain.exception.LoanExceptions.LoanNotFoundException;
import ke.chama.modules.loan.domain.exception.LoanExceptions.NotASeatedMemberException;
import ke.chama.modules.loan.domain.model.Loan;
import ke.chama.modules.loan.domain.model.LoanEligibility;
import ke.chama.modules.loan.domain.model.LoanProduct;
import ke.chama.modules.account.api.MemberAccounts;
import ke.chama.modules.loan.domain.model.LoanRepayment;
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
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Loans from application to the last repayment.
 * <p>
 * Ledger: disbursement debits the borrower's "Loans receivable" with principal plus the flat
 * interest, credits the account the money left from with the principal and "Loan interest
 * income" with the interest. Repayments debit the account the money came into and credit the
 * borrower's "Loans receivable". So the borrower's receivable is always what they still owe.
 */
@Service
@Transactional
public class LoanService implements LoanUseCases {

    static final String SOURCE_DISBURSEMENT = "LOAN_DISBURSEMENT";
    static final String SOURCE_REPAYMENT = "LOAN_REPAYMENT";
    static final String AUDIT_TARGET = "LOAN";

    private final LoanRepository repository;
    private final LoanProductService products;
    private final MemberDirectory members;
    private final MemberAccounts memberAccounts;
    private final ChamaDirectory chamas;
    private final Ledger ledger;
    private final AuditTrail auditTrail;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final ZoneId zone;

    public LoanService(LoanRepository repository, LoanProductService products, MemberDirectory members,
                       MemberAccounts memberAccounts, ChamaDirectory chamas, Ledger ledger,
                       AuditTrail auditTrail, ApplicationEventPublisher events, Clock clock,
                       @Value("${chama.time-zone:Africa/Nairobi}") String timeZone) {
        this.repository = repository;
        this.products = products;
        this.members = members;
        this.memberAccounts = memberAccounts;
        this.chamas = chamas;
        this.ledger = ledger;
        this.auditTrail = auditTrail;
        this.events = events;
        this.clock = clock;
        this.zone = ZoneId.of(timeZone);
    }

    // ---- applying -------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public EligibilityView eligibility(UUID chamaId, UUID memberUserId, UUID productId) {
        LoanProduct product = products.forApplication(chamaId, productId);
        LoanRules rules = rules(chamaId, product);
        LoanEligibility e = assess(chamaId, memberUserId, product, rules);
        return new EligibilityView(e.eligible(), e.reasons(), product.id(), product.code(), product.name(),
                rules.currency(), e.savingsMinor(), e.otherLoansMinor(), e.maxLoanMinor(), rules.maxMultiplier(),
                rules.interestBasisPoints(), product.maxTermMonths(), e.membershipMonths(), rules.minMembershipMonths(),
                rules.guarantorThresholdMinor(), rules.guarantorsRequired(), rules.approvalQuorum());
    }

    @Override
    public LoanDetailView apply(UUID chamaId, UUID borrowerUserId, Application application) {
        LoanProduct product = products.forApplication(chamaId, application.productId());
        LoanRules rules = rules(chamaId, product);
        assess(chamaId, borrowerUserId, product, rules).requireAllows(application.principalMinor(),
                application.termMonths());
        List<UUID> guarantorIds = application.guarantorUserIds() == null ? List.of() : application.guarantorUserIds();
        List<MemberSummary> guarantors = new ArrayList<>();
        for (UUID id : guarantorIds) {
            if (!id.equals(borrowerUserId)) {
                guarantors.add(activeMember(chamaId, id, "A guarantor you named"));
            }
        }
        Loan loan = Loan.apply(chamaId, borrowerUserId, product.id(), application.principalMinor(), application.termMonths(),
                application.purpose(), rules.currency(), rules.interestBasisPoints(),
                rules.guarantorsFor(application.principalMinor()), rules.approvalQuorum(), guarantorIds, today(),
                clock.instant());
        repository.insert(loan);
        audit(loan, borrowerUserId, "LOAN_APPLIED", Map.of("productCode", product.code(),
                "principalMinor", loan.principalMinor(),
                "termMonths", loan.termMonths(), "interestMinor", loan.interestMinor(), "purpose", loan.purpose(),
                "guarantors", guarantorIds.stream().map(UUID::toString).toList()));
        guarantors.forEach(g -> askToGuarantee(loan, g));
        return detail(loan);
    }

    @Override
    public LoanDetailView addGuarantor(UUID chamaId, UUID actorUserId, UUID loanId, UUID guarantorUserId) {
        Loan loan = load(chamaId, loanId);
        MemberSummary guarantor = activeMember(chamaId, guarantorUserId, "That guarantor");
        loan.addGuarantor(actorUserId, guarantorUserId, clock.instant());
        save(loan);
        audit(loan, actorUserId, "LOAN_GUARANTOR_ADDED", Map.of("guarantorUserId", guarantorUserId.toString()));
        askToGuarantee(loan, guarantor);
        return detail(loan);
    }

    @Override
    public LoanDetailView respondAsGuarantor(UUID chamaId, UUID guarantorUserId, UUID loanId, boolean accept) {
        Loan loan = load(chamaId, loanId);
        loan.respondAsGuarantor(guarantorUserId, accept, clock.instant());
        save(loan);
        audit(loan, guarantorUserId, accept ? "LOAN_GUARANTEE_ACCEPTED" : "LOAN_GUARANTEE_DECLINED",
                Map.of("acceptedGuarantors", loan.acceptedGuarantors(), "requiredGuarantors", loan.requiredGuarantors()));
        return detail(loan);
    }

    @Override
    public LoanDetailView cancel(UUID chamaId, UUID actorUserId, UUID loanId) {
        Loan loan = load(chamaId, loanId);
        loan.cancel(actorUserId, clock.instant());
        save(loan);
        audit(loan, actorUserId, "LOAN_CANCELLED", Map.of());
        return detail(loan);
    }

    // ---- deciding -------------------------------------------------------------------------------

    @Override
    public LoanDetailView approve(UUID chamaId, UUID actorUserId, UUID loanId, String note) {
        Loan loan = load(chamaId, loanId);
        loan.approve(actorUserId, note, clock.instant());
        save(loan);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("approvals", loan.approvals().size());
        details.put("requiredApprovals", loan.requiredApprovals());
        if (note != null && !note.isBlank()) {
            details.put("note", note.trim());
        }
        audit(loan, actorUserId, "LOAN_APPROVAL_GIVEN", details);
        if (loan.status() == Loan.Status.APPROVED) {
            audit(loan, actorUserId, "LOAN_APPROVED", Map.of("principalMinor", loan.principalMinor()));
            tellBorrower(loan);
        }
        return detail(loan);
    }

    @Override
    public LoanDetailView reject(UUID chamaId, UUID actorUserId, UUID loanId, String reason) {
        Loan loan = load(chamaId, loanId);
        loan.reject(actorUserId, reason, clock.instant());
        save(loan);
        audit(loan, actorUserId, "LOAN_REJECTED", Map.of("reason", loan.rejectionReason()));
        tellBorrower(loan);
        return detail(loan);
    }

    // ---- money ----------------------------------------------------------------------------------

    @Override
    public LoanDetailView disburse(UUID chamaId, UUID actorUserId, UUID loanId, Transaction disbursement) {
        Loan loan = load(chamaId, loanId);
        loan.disburse(actorUserId, disbursement.method(), disbursement.reference(), disbursement.date(), today());
        requireUnusedReference(chamaId, loan.disbursementMethod(), loan.disbursementReference());

        LedgerAccountKey source = accountFor(loan.disbursementMethod());
        Money principal = money(loan.principalMinor(), loan.currency());
        Money available = ledger.balance(chamaId, source);
        if (available.isLessThan(principal)) {
            throw new InsufficientFundsException(accountName(source), Loan.formatted(available.minorUnits(), loan.currency()));
        }
        List<PostingLine> lines = new ArrayList<>();
        lines.add(PostingLine.debit(LedgerAccountKey.LOANS_RECEIVABLE, money(loan.totalDueMinor(), loan.currency()))
                .forMember(loan.borrowerUserId()));
        lines.add(PostingLine.credit(source, principal));
        if (loan.interestMinor() > 0) {
            lines.add(PostingLine.credit(LedgerAccountKey.INTEREST_INCOME, money(loan.interestMinor(), loan.currency())));
        }
        UUID entryId = ledger.post(new PostingRequest(chamaId, loan.disbursedOn(), "Loan disbursed: " + loan.purpose(),
                loan.disbursementReference(), SOURCE_DISBURSEMENT, loan.id().toString(), actorUserId, lines));
        loan.disbursementPostedAs(entryId);
        save(loan);
        audit(loan, actorUserId, "LOAN_DISBURSED", Map.of("principalMinor", loan.principalMinor(),
                "interestMinor", loan.interestMinor(), "method", loan.disbursementMethod(),
                "maturityDate", loan.maturityDate().toString()));
        tellBorrower(loan);
        return detail(loan);
    }

    @Override
    public LoanDetailView recordRepayment(UUID chamaId, UUID actorUserId, UUID loanId, Repayment request) {
        Loan loan = load(chamaId, loanId);
        LoanRepayment repayment = LoanRepayment.record(loan, request.amountMinor(), request.method(),
                request.reference(), request.paidOn(), actorUserId, today(), clock.instant());
        loan.repay(actorUserId, repayment.amountMinor());
        requireUnusedReference(chamaId, repayment.method(), repayment.reference());

        Money amount = money(repayment.amountMinor(), loan.currency());
        UUID entryId = ledger.post(new PostingRequest(chamaId, repayment.paidOn(), "Loan repayment: " + loan.purpose(),
                repayment.reference(), SOURCE_REPAYMENT, repayment.id().toString(), actorUserId, List.of(
                PostingLine.debit(accountFor(repayment.method()), amount),
                PostingLine.credit(LedgerAccountKey.LOANS_RECEIVABLE, amount).forMember(loan.borrowerUserId()))));
        repository.insertRepayment(repayment.postedAs(entryId));
        save(loan);
        audit(loan, actorUserId, "LOAN_REPAYMENT_RECORDED", Map.of("amountMinor", repayment.amountMinor(),
                "method", repayment.method(), "outstandingMinor", loan.outstandingMinor()));
        if (loan.status() == Loan.Status.REPAID) {
            audit(loan, actorUserId, "LOAN_REPAID", Map.of("totalRepaidMinor", loan.repaidMinor()));
            tellBorrower(loan);
        }
        return detail(loan);
    }

    // ---- queries --------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public LoanDetailView loan(UUID chamaId, UUID loanId) {
        return detail(load(chamaId, loanId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<LoanView> loans(UUID chamaId, UUID borrowerUserId, Loan.Status status, Pageable pageable) {
        Map<UUID, MemberSummary> seated = seatedById(chamaId);
        Map<UUID, LoanProduct> catalog = products.byId(chamaId);
        LocalDate today = today();
        return repository.loans(chamaId, borrowerUserId, status, pageable).map(l -> view(l, seated, catalog, today));
    }

    @Override
    @Transactional(readOnly = true)
    public MyLoansView mine(UUID chamaId, UUID memberUserId) {
        Map<UUID, MemberSummary> seated = seatedById(chamaId);
        Map<UUID, LoanProduct> catalog = products.byId(chamaId);
        LocalDate today = today();
        List<Loan> loans = repository.loansOf(chamaId, memberUserId);
        List<LoanDetailView> details = loans.stream().map(l -> detail(l, seated, catalog, today)).toList();
        return new MyLoansView(rules(chamaId).currency(),
                loans.stream().mapToLong(Loan::outstandingMinor).sum(),
                loans.stream().mapToLong(l -> l.arrearsMinor(today)).sum(), details);
    }

    @Override
    @Transactional(readOnly = true)
    public List<GuaranteeView> guaranteeRequests(UUID chamaId, UUID memberUserId) {
        Map<UUID, MemberSummary> seated = seatedById(chamaId);
        Map<UUID, LoanProduct> catalog = products.byId(chamaId);
        LocalDate today = today();
        return repository.guaranteedBy(chamaId, memberUserId).stream()
                .map(l -> new GuaranteeView(view(l, seated, catalog, today),
                        l.guarantor(memberUserId).map(Loan.Guarantor::status).orElse(null)))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<GuarantorCandidate> guarantorCandidates(UUID chamaId, UUID memberUserId) {
        return members.seatedMembers(chamaId).stream()
                .filter(MemberSummary::active)
                .filter(m -> !m.userId().equals(memberUserId))
                .map(m -> new GuarantorCandidate(m.userId(), m.memberNumber(),
                        name(m).isEmpty() ? m.email() : name(m)))
                .toList();
    }

    // ---- helpers --------------------------------------------------------------------------------

    /**
     * Savings are the member's balances in the products that count toward the loan limit; loans of
     * other products in progress use up part of the limit (what is still owed once disbursed, the
     * principal applied for before that).
     */
    private LoanEligibility assess(UUID chamaId, UUID memberUserId, LoanProduct product, LoanRules rules) {
        MemberSummary member = members.seatedMember(chamaId, memberUserId).orElse(null);
        long savings = memberAccounts.loanBackingSavingsMinor(chamaId, memberUserId);
        long otherLoans = repository.loansOf(chamaId, memberUserId).stream()
                .filter(l -> l.status().open() && !l.productId().equals(product.id()))
                .mapToLong(l -> l.status() == Loan.Status.ACTIVE ? l.outstandingMinor() : l.principalMinor())
                .sum();
        LocalDate joinedOn = member == null || member.joinedAt() == null ? null : LocalDate.ofInstant(member.joinedAt(), zone);
        return LoanEligibility.assess(rules, product.name(), product.maxTermMonths(), member != null && member.active(),
                savings, otherLoans, joinedOn, today(), repository.hasOpenLoan(chamaId, memberUserId, product.id()));
    }

    private MemberSummary activeMember(UUID chamaId, UUID userId, String who) {
        return members.seatedMember(chamaId, userId).filter(MemberSummary::active)
                .orElseThrow(() -> new NotASeatedMemberException(who));
    }

    private void askToGuarantee(Loan loan, MemberSummary guarantor) {
        MemberSummary borrower = members.seatedMember(loan.chamaId(), loan.borrowerUserId()).orElse(null);
        events.publishEvent(new LoanGuarantorRequested(UUID.randomUUID(), clock.instant(), loan.chamaId(),
                chamaName(loan.chamaId()), loan.id(), guarantor.userId(), guarantor.email(), guarantor.firstName(),
                borrower == null ? "A member" : borrower.fullName().trim(), loan.principalMinor(), loan.currency(),
                loan.termMonths(), loan.purpose()));
    }

    private void tellBorrower(Loan loan) {
        MemberSummary borrower = members.seatedMember(loan.chamaId(), loan.borrowerUserId()).orElse(null);
        if (borrower == null || borrower.email() == null) {
            return;
        }
        Loan.Instalment first = loan.schedule(today()).stream().findFirst().orElse(null);
        events.publishEvent(new LoanStatusChanged(UUID.randomUUID(), clock.instant(), loan.chamaId(),
                chamaName(loan.chamaId()), loan.id(), borrower.userId(), borrower.email(), borrower.firstName(),
                loan.status().name(), loan.principalMinor(), loan.totalDueMinor(), loan.currency(),
                loan.rejectionReason(), first == null ? null : first.dueDate(), first == null ? 0 : first.amountMinor()));
    }

    private void requireUnusedReference(UUID chamaId, String method, String reference) {
        if (reference != null && repository.referenceExists(chamaId, method, reference)) {
            throw new DuplicateReferenceException(reference);
        }
    }

    private void audit(Loan loan, UUID actorUserId, String action, Map<String, ?> details) {
        Map<String, Object> all = new HashMap<>(details);
        all.put("borrowerUserId", loan.borrowerUserId().toString());
        auditTrail.record(AuditEntry.of(loan.chamaId(), actorUserId, action, AUDIT_TARGET, loan.id()).with(all));
    }

    private void save(Loan loan) {
        if (!repository.update(loan)) {
            throw new OptimisticLockingFailureException("Loan " + loan.id() + " changed concurrently");
        }
    }

    private Loan load(UUID chamaId, UUID loanId) {
        return repository.loan(chamaId, loanId).orElseThrow(LoanNotFoundException::new);
    }

    private LoanRules rules(UUID chamaId) {
        return chamas.loanRules(chamaId).orElseThrow();
    }

    /** The product's terms with the chama's currency and approval quorum. */
    private LoanRules rules(UUID chamaId, LoanProduct product) {
        LoanRules chama = rules(chamaId);
        return product.rules(chama.currency(), chama.approvalQuorum());
    }

    private String chamaName(UUID chamaId) {
        return chamas.find(chamaId).map(ChamaSummary::name).orElse("your chama");
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

    private static String accountName(LedgerAccountKey key) {
        return switch (key) {
            case BANK -> "bank account";
            case MOBILE_MONEY -> "M-Pesa account";
            default -> "cash on hand";
        };
    }

    private static Money money(long minor, String currency) {
        return Money.of(minor, Currency.getInstance(currency));
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(zone));
    }

    // ---- views ----------------------------------------------------------------------------------

    private LoanDetailView detail(Loan loan) {
        return detail(loan, seatedById(loan.chamaId()), products.byId(loan.chamaId()), today());
    }

    private LoanDetailView detail(Loan loan, Map<UUID, MemberSummary> seated, Map<UUID, LoanProduct> catalog,
                                  LocalDate today) {
        List<GuarantorView> guarantors = loan.guarantors().stream().map(g -> {
            MemberSummary m = seated.get(g.userId());
            return new GuarantorView(g.userId(), name(m), m == null ? null : m.memberNumber(), g.status(),
                    g.requestedAt(), g.respondedAt());
        }).toList();
        List<ApprovalView> approvals = loan.approvals().stream()
                .map(a -> new ApprovalView(a.approverUserId(), name(seated.get(a.approverUserId())), a.note(), a.approvedAt()))
                .toList();
        DisbursementView disbursement = loan.disbursedOn() == null ? null : new DisbursementView(loan.disbursedOn(),
                loan.disbursedBy(), loan.disbursementMethod(), loan.disbursementReference(), loan.disbursementEntryId());
        DecisionView decision = loan.rejectedAt() == null && loan.cancelledAt() == null ? null
                : new DecisionView(loan.rejectedBy(), loan.rejectedAt(), loan.rejectionReason(), loan.cancelledAt());
        List<InstalmentView> schedule = loan.schedule(today).stream()
                .map(i -> new InstalmentView(i.number(), i.dueDate(), i.amountMinor(), i.paidMinor(), i.status()))
                .toList();
        List<RepaymentView> repayments = loan.disbursedOn() == null ? List.of()
                : repository.repayments(loan.chamaId(), loan.id()).stream()
                .map(r -> new RepaymentView(r.id(), r.amountMinor(), r.method(), r.reference(), r.paidOn(),
                        r.ledgerEntryId(), r.recordedBy(), r.recordedAt()))
                .toList();
        return new LoanDetailView(view(loan, seated, catalog, today), guarantors, approvals, disbursement, decision, schedule,
                repayments);
    }

    private static LoanView view(Loan l, Map<UUID, MemberSummary> seated, Map<UUID, LoanProduct> catalog,
                                 LocalDate today) {
        MemberSummary borrower = seated.get(l.borrowerUserId());
        LoanProduct product = catalog.get(l.productId());
        Loan.Instalment next = l.status() == Loan.Status.ACTIVE ? l.nextInstalment(today).orElse(null) : null;
        return new LoanView(l.id(), l.borrowerUserId(), name(borrower), borrower == null ? null : borrower.memberNumber(),
                l.productId(), product == null ? null : product.code(), product == null ? null : product.name(),
                l.principalMinor(), l.interestBasisPoints(), l.interestMinor(), l.totalDueMinor(), l.repaidMinor(),
                l.outstandingMinor(), l.arrearsMinor(today), l.currency(), l.termMonths(), l.purpose(), l.status(),
                l.appliedOn(), l.requiredGuarantors(), l.acceptedGuarantors(), l.requiredApprovals(),
                l.approvals().size(), l.disbursedOn(), next == null ? null : next.dueDate(),
                next == null ? 0 : next.unpaidMinor(), l.maturityDate(), l.rejectionReason());
    }

    private static String name(MemberSummary member) {
        return member == null ? null : member.fullName().trim();
    }
}
