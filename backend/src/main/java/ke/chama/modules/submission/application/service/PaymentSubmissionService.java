package ke.chama.modules.submission.application.service;

import ke.chama.modules.audit.api.AuditEntry;
import ke.chama.modules.audit.api.AuditTrail;
import ke.chama.modules.chama.api.ChamaDirectory;
import ke.chama.modules.chama.api.ChamaSummary;
import ke.chama.modules.contribution.api.ContributionRecording;
import ke.chama.modules.loan.api.LoanRepayments;
import ke.chama.modules.loan.api.LoanRepayments.RepayableLoan;
import ke.chama.modules.member.api.MemberDirectory;
import ke.chama.modules.member.api.MemberPermissions;
import ke.chama.modules.member.api.MemberSummary;
import ke.chama.modules.penalty.api.PenaltyPayments;
import ke.chama.modules.penalty.api.PenaltyPayments.OwedPenalty;
import ke.chama.modules.submission.api.PaymentSubmissionQueue;
import ke.chama.modules.submission.application.port.in.PaymentSubmissionUseCases;
import ke.chama.modules.submission.application.port.out.PaymentSubmissionRepository;
import ke.chama.modules.submission.domain.event.PaymentSubmissionReviewed;
import ke.chama.modules.submission.domain.exception.SubmissionExceptions.DuplicateReferenceException;
import ke.chama.modules.submission.domain.exception.SubmissionExceptions.FeatureNotLicensedException;
import ke.chama.modules.submission.domain.exception.SubmissionExceptions.InvalidTargetException;
import ke.chama.modules.submission.domain.exception.SubmissionExceptions.ReviewNotAllowedException;
import ke.chama.modules.submission.domain.exception.SubmissionExceptions.SubmissionNotFoundException;
import ke.chama.modules.submission.domain.model.PaymentSubmission;
import ke.chama.modules.submission.domain.model.PaymentSubmission.Kind;
import ke.chama.shared.security.ChamaPermission;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Currency;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Reported payments. Approving one records the real payment through the module that owns it
 * (contribution, penalty or loan) in the same transaction, under the approving official's name,
 * so every rule of a hand-entered payment applies: the reference must be unused, a penalty or
 * loan cannot be overpaid, and nobody records their own money.
 */
@Service
@Transactional
public class PaymentSubmissionService implements PaymentSubmissionUseCases, PaymentSubmissionQueue {

    static final String AUDIT_TARGET = "PAYMENT_SUBMISSION";

    private final PaymentSubmissionRepository repository;
    private final MemberDirectory members;
    private final MemberPermissions permissions;
    private final ChamaDirectory chamas;
    private final ContributionRecording contributions;
    private final PenaltyPayments penalties;
    private final LoanRepayments loans;
    private final AuditTrail auditTrail;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final ZoneId zone;

    public PaymentSubmissionService(PaymentSubmissionRepository repository, MemberDirectory members,
                                    MemberPermissions permissions, ChamaDirectory chamas,
                                    ContributionRecording contributions, PenaltyPayments penalties,
                                    LoanRepayments loans, AuditTrail auditTrail, ApplicationEventPublisher events,
                                    Clock clock, @Value("${chama.time-zone:Africa/Nairobi}") String timeZone) {
        this.repository = repository;
        this.members = members;
        this.permissions = permissions;
        this.chamas = chamas;
        this.contributions = contributions;
        this.penalties = penalties;
        this.loans = loans;
        this.auditTrail = auditTrail;
        this.events = events;
        this.clock = clock;
        this.zone = ZoneId.of(timeZone);
    }

    // ---- member ---------------------------------------------------------------------------------

    @Override
    public SubmissionView submit(UUID chamaId, UUID memberUserId, NewSubmission request) {
        PaymentSubmission submission = PaymentSubmission.submit(chamaId, memberUserId, request.kind(),
                request.targetId(), request.amountMinor(), currency(chamaId), request.method(), request.reference(),
                request.paidOn(), request.notes(), today(), clock.instant());
        requireLicensed(chamaId, submission.kind());
        checkTarget(submission);
        if (submission.reference() != null
                && (repository.referenceTaken(chamaId, submission.method(), submission.reference())
                || referenceRecorded(submission))) {
            throw new DuplicateReferenceException(submission.reference());
        }
        repository.insert(submission);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("kind", submission.kind().name());
        details.put("amountMinor", submission.amountMinor());
        details.put("method", submission.method());
        details.put("reference", submission.reference() == null ? "" : submission.reference());
        if (submission.targetId() != null) {
            details.put("targetId", submission.targetId().toString());
        }
        audit(submission, memberUserId, "PAYMENT_SUBMITTED", details);
        return view(submission, seatedById(chamaId));
    }

    @Override
    public SubmissionView cancel(UUID chamaId, UUID actorUserId, UUID submissionId) {
        PaymentSubmission submission = load(chamaId, submissionId);
        submission.cancel(actorUserId, clock.instant());
        save(submission);
        audit(submission, actorUserId, "PAYMENT_SUBMISSION_CANCELLED", Map.of());
        return view(submission, seatedById(chamaId));
    }

    // ---- officials ------------------------------------------------------------------------------

    @Override
    public SubmissionView approve(UUID chamaId, UUID officialUserId, UUID submissionId, String note) {
        PaymentSubmission submission = load(chamaId, submissionId);
        requireReviewer(chamaId, officialUserId, submission.kind());
        submission.approve(officialUserId, note, clock.instant());
        save(submission);
        switch (submission.kind()) {
            case CONTRIBUTION -> contributions.record(chamaId, officialUserId, submission.memberUserId(),
                    submission.amountMinor(), submission.method(), submission.reference(), submission.paidOn(),
                    submission.notes());
            case PENALTY_PAYMENT -> penalties.recordPayment(chamaId, officialUserId, submission.targetId(),
                    submission.amountMinor(), submission.method(), submission.reference(), submission.paidOn());
            case LOAN_REPAYMENT -> loans.recordRepayment(chamaId, officialUserId, submission.targetId(),
                    submission.amountMinor(), submission.method(), submission.reference(), submission.paidOn());
        }
        audit(submission, officialUserId, "PAYMENT_SUBMISSION_APPROVED", Map.of("kind", submission.kind().name(),
                "amountMinor", submission.amountMinor()));
        Map<UUID, MemberSummary> seated = seatedById(chamaId);
        tellMember(submission, seated.get(submission.memberUserId()));
        return view(submission, seated);
    }

    @Override
    public SubmissionView reject(UUID chamaId, UUID officialUserId, UUID submissionId, String reason) {
        PaymentSubmission submission = load(chamaId, submissionId);
        requireReviewer(chamaId, officialUserId, submission.kind());
        submission.reject(officialUserId, reason, clock.instant());
        save(submission);
        audit(submission, officialUserId, "PAYMENT_SUBMISSION_REJECTED", Map.of("kind", submission.kind().name(),
                "reason", submission.reviewNote()));
        Map<UUID, MemberSummary> seated = seatedById(chamaId);
        tellMember(submission, seated.get(submission.memberUserId()));
        return view(submission, seated);
    }

    // ---- queries --------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public SubmissionView submission(UUID chamaId, UUID callerUserId, UUID submissionId) {
        PaymentSubmission submission = load(chamaId, submissionId);
        if (!submission.memberUserId().equals(callerUserId)) {
            requireReviewer(chamaId, callerUserId, submission.kind());
        }
        return view(submission, seatedById(chamaId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SubmissionView> reviewQueue(UUID chamaId, UUID officialUserId, PaymentSubmission.Status status,
                                            Kind kind, UUID memberUserId, Pageable pageable) {
        Set<Kind> kinds = EnumSet.noneOf(Kind.class);
        kinds.addAll(reviewableKinds(chamaId, officialUserId));
        if (kind != null) {
            kinds.retainAll(Set.of(kind));
        }
        if (kinds.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, 0);
        }
        Map<UUID, MemberSummary> seated = seatedById(chamaId);
        return repository.submissions(chamaId, kinds, status, memberUserId, pageable).map(s -> view(s, seated));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SubmissionView> mine(UUID chamaId, UUID memberUserId, PaymentSubmission.Status status, Pageable pageable) {
        Map<UUID, MemberSummary> seated = seatedById(chamaId);
        return repository.submissions(chamaId, EnumSet.allOf(Kind.class), status, memberUserId, pageable)
                .map(s -> view(s, seated));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Kind> reviewableKinds(UUID chamaId, UUID officialUserId) {
        Set<ChamaPermission> held = permissions.permissionsOf(chamaId, officialUserId);
        return Arrays.stream(Kind.values()).filter(k -> held.contains(k.reviewPermission()))
                .filter(k -> licensed(chamaId, k)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ReviewCounts reviewCounts(UUID chamaId, UUID officialUserId) {
        List<Kind> reviewable = reviewableKinds(chamaId, officialUserId);
        Map<Kind, Long> counts = repository.pendingByKindExcluding(chamaId, officialUserId);
        Function<Kind, Long> count = k -> reviewable.contains(k) ? counts.getOrDefault(k, 0L) : 0L;
        return new ReviewCounts(count.apply(Kind.CONTRIBUTION), count.apply(Kind.PENALTY_PAYMENT),
                count.apply(Kind.LOAN_REPAYMENT));
    }

    @Override
    @Transactional(readOnly = true)
    public PendingCounts pending(UUID chamaId) {
        Map<Kind, Long> counts = repository.pendingByKind(chamaId);
        return new PendingCounts(counts.getOrDefault(Kind.CONTRIBUTION, 0L),
                counts.getOrDefault(Kind.PENALTY_PAYMENT, 0L), counts.getOrDefault(Kind.LOAN_REPAYMENT, 0L));
    }

    @Override
    @Transactional(readOnly = true)
    public long pendingOf(UUID chamaId, UUID memberUserId) {
        return repository.pendingOf(chamaId, memberUserId);
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** The penalty or loan must be the member's own and able to take this payment now. */
    private void checkTarget(PaymentSubmission s) {
        switch (s.kind()) {
            case CONTRIBUTION -> {
            }
            case PENALTY_PAYMENT -> {
                OwedPenalty penalty = penalties.penalty(s.chamaId(), s.targetId())
                        .filter(p -> p.memberUserId().equals(s.memberUserId()))
                        .orElseThrow(() -> new InvalidTargetException("That penalty is not one of yours"));
                if (!penalty.outstanding()) {
                    throw new InvalidTargetException("That penalty is already " + penalty.status().toLowerCase());
                }
                if (s.amountMinor() > penalty.outstandingMinor()) {
                    throw new InvalidTargetException("Only " + formatted(penalty.outstandingMinor(), penalty.currency())
                            + " is outstanding on that penalty");
                }
            }
            case LOAN_REPAYMENT -> {
                RepayableLoan loan = loans.loan(s.chamaId(), s.targetId())
                        .filter(l -> l.borrowerUserId().equals(s.memberUserId()))
                        .orElseThrow(() -> new InvalidTargetException("That loan is not one of yours"));
                if (!loan.active()) {
                    throw new InvalidTargetException("Repayments are only taken on an active loan; this one is "
                            + loan.status().toLowerCase().replace('_', ' '));
                }
                if (s.amountMinor() > loan.outstandingMinor()) {
                    throw new InvalidTargetException("Only " + formatted(loan.outstandingMinor(), loan.currency())
                            + " is outstanding on that loan");
                }
            }
        }
    }

    private boolean referenceRecorded(PaymentSubmission s) {
        return switch (s.kind()) {
            case CONTRIBUTION -> contributions.referenceUsed(s.chamaId(), s.method(), s.reference());
            case PENALTY_PAYMENT -> penalties.referenceUsed(s.chamaId(), s.method(), s.reference());
            case LOAN_REPAYMENT -> loans.referenceUsed(s.chamaId(), s.method(), s.reference());
        };
    }

    private void requireReviewer(UUID chamaId, UUID officialUserId, Kind kind) {
        if (!permissions.permissionsOf(chamaId, officialUserId).contains(kind.reviewPermission())) {
            throw new ReviewNotAllowedException(kind);
        }
        requireLicensed(chamaId, kind);
    }

    private void requireLicensed(UUID chamaId, Kind kind) {
        if (!licensed(chamaId, kind)) {
            throw new FeatureNotLicensedException(kind.feature().name());
        }
    }

    /** A chama created before licensing has every feature. */
    private boolean licensed(UUID chamaId, Kind kind) {
        return chamas.license(chamaId).map(l -> l.includes(kind.feature())).orElse(true);
    }

    private void tellMember(PaymentSubmission s, MemberSummary member) {
        if (member == null) {
            return;
        }
        events.publishEvent(new PaymentSubmissionReviewed(UUID.randomUUID(), clock.instant(), s.chamaId(),
                chamas.find(s.chamaId()).map(ChamaSummary::name).orElse("Your chama"), s.id(), s.memberUserId(),
                member.email(), member.firstName(), s.kind().name(), s.amountMinor(), s.currency(), s.method(),
                s.reference(), s.paidOn(), s.status() == PaymentSubmission.Status.APPROVED, s.reviewNote()));
    }

    private SubmissionView view(PaymentSubmission s, Map<UUID, MemberSummary> seated) {
        MemberSummary member = seated.get(s.memberUserId());
        MemberSummary reviewer = s.reviewedBy() == null ? null : seated.get(s.reviewedBy());
        return new SubmissionView(s.id(), s.memberUserId(), name(member), member == null ? null : member.memberNumber(),
                s.kind(), s.targetId(), targetDescription(s), s.amountMinor(), s.currency(), s.method(), s.reference(),
                s.paidOn(), s.notes(), s.status(), s.submittedAt(), s.reviewedBy(), name(reviewer), s.reviewedAt(),
                s.reviewNote(), s.cancelledAt());
    }

    private String targetDescription(PaymentSubmission s) {
        return switch (s.kind()) {
            case CONTRIBUTION -> null;
            case PENALTY_PAYMENT -> penalties.penalty(s.chamaId(), s.targetId()).map(OwedPenalty::description).orElse(null);
            case LOAN_REPAYMENT -> loans.loan(s.chamaId(), s.targetId()).map(l -> "Loan: " + l.purpose()).orElse(null);
        };
    }

    private static String name(MemberSummary member) {
        if (member == null) {
            return null;
        }
        String name = member.fullName().trim();
        return name.isEmpty() ? member.email() : name;
    }

    private Map<UUID, MemberSummary> seatedById(UUID chamaId) {
        return members.seatedMembers(chamaId).stream().collect(Collectors.toMap(MemberSummary::userId, Function.identity()));
    }

    private void save(PaymentSubmission submission) {
        if (!repository.update(submission)) {
            throw new OptimisticLockingFailureException("Payment submission " + submission.id() + " changed concurrently");
        }
    }

    private PaymentSubmission load(UUID chamaId, UUID submissionId) {
        return repository.submission(chamaId, submissionId).orElseThrow(SubmissionNotFoundException::new);
    }

    private void audit(PaymentSubmission s, UUID actorUserId, String action, Map<String, ?> details) {
        auditTrail.record(AuditEntry.of(s.chamaId(), actorUserId, action, AUDIT_TARGET, s.id()).with(details));
    }

    private String currency(UUID chamaId) {
        return chamas.find(chamaId).map(ChamaSummary::currency).orElse("KES");
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(zone));
    }

    /** E.g. "KES 5,000.00". */
    static String formatted(long minor, String currency) {
        int digits = Math.max(Currency.getInstance(currency).getDefaultFractionDigits(), 0);
        return currency + " " + new DecimalFormat(digits == 0 ? "#,##0" : "#,##0." + "0".repeat(digits))
                .format(BigDecimal.valueOf(minor, digits));
    }
}
