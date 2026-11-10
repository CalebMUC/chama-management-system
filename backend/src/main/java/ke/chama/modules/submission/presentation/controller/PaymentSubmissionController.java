package ke.chama.modules.submission.presentation.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import ke.chama.modules.submission.application.port.in.PaymentSubmissionUseCases;
import ke.chama.modules.submission.application.port.in.PaymentSubmissionUseCases.SubmissionView;
import ke.chama.modules.submission.domain.model.PaymentSubmission;
import ke.chama.modules.submission.presentation.dto.request.SubmissionRequests.ApproveSubmissionRequest;
import ke.chama.modules.submission.presentation.dto.request.SubmissionRequests.RejectSubmissionRequest;
import ke.chama.modules.submission.presentation.dto.request.SubmissionRequests.SubmitPaymentRequest;
import ke.chama.shared.security.ChamaPermission;
import ke.chama.shared.security.CurrentUser;
import ke.chama.shared.security.RequiresChamaMembership;
import ke.chama.shared.security.RequiresChamaPermission;
import ke.chama.shared.tenancy.TenantContext;
import ke.chama.shared.web.ApiResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Payments members report themselves, and the officials' review queue. A report needs the
 * licence feature of what was paid (CONTRIBUTIONS, PENALTIES or LOANS); reviewing one needs the
 * matching record permission (CONTRIBUTION_RECORD, PENALTY_RECORD or LOAN_REPAYMENT_RECORD).
 * <pre>
 * POST /api/v1/payment-submissions                  PAYMENT_SUBMIT   {kind, targetId, amountMinor, method, reference, paidOn, notes}
 * GET  /api/v1/payment-submissions/me               active member    ?status=&page=&size=  my reports, pending first
 * POST /api/v1/payment-submissions/{id}/cancel      active member    the submitter withdraws a pending report
 * GET  /api/v1/payment-submissions                  any record perm  ?status=&kind=&memberUserId=&page=&size=  the review queue
 * GET  /api/v1/payment-submissions/reviewable-kinds any record perm  the kinds I may review
 * GET  /api/v1/payment-submissions/{id}             submitter or reviewer of its kind
 * POST /api/v1/payment-submissions/{id}/approve     record perm of its kind  {note}: records the real payment
 * POST /api/v1/payment-submissions/{id}/reject      record perm of its kind  {reason}
 * </pre>
 */
@Tag(name = "Payment submissions",
        description = "Members report their own contributions, penalty payments and loan repayments; the treasurer confirms them")
@RestController
@RequestMapping("/api/v1/payment-submissions")
class PaymentSubmissionController {

    /** Holds the permission to review at least one kind of payment. */
    static final String ANY_REVIEWER =
            "@chamaAccess.hasAny('CONTRIBUTION_RECORD', 'PENALTY_RECORD', 'LOAN_REPAYMENT_RECORD')";

    private static final int MAX_PAGE_SIZE = 100;

    private final PaymentSubmissionUseCases submissions;

    PaymentSubmissionController(PaymentSubmissionUseCases submissions) {
        this.submissions = submissions;
    }

    // ---- members --------------------------------------------------------------------------------

    @Operation(summary = "Report a payment I made",
            description = """
                    A contribution, a penalty payment (targetId = the penalty) or a loan repayment (targetId = the \
                    loan), with its M-Pesa code or bank slip number. Nothing is recorded until an official (the \
                    treasurer by default) checks it and approves it.""")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresChamaPermission(ChamaPermission.PAYMENT_SUBMIT)
    ApiResponse<SubmissionView> submit(@Valid @RequestBody SubmitPaymentRequest request) {
        return ApiResponse.ok(submissions.submit(chama(), CurrentUser.id(), request.toCommand()),
                "Payment reported. The treasurer will confirm it once they have checked it.");
    }

    @Operation(summary = "My reported payments", description = "Pending first, then the rest newest first.")
    @GetMapping("/me")
    @RequiresChamaMembership
    ApiResponse<List<SubmissionView>> mine(@RequestParam(required = false) PaymentSubmission.Status status,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.page(submissions.mine(chama(), CurrentUser.id(), status, pageable(page, size)));
    }

    @Operation(summary = "Withdraw a report", description = "Submitter only, while it is still pending.")
    @PostMapping("/{submissionId}/cancel")
    @RequiresChamaMembership
    ApiResponse<SubmissionView> cancel(@PathVariable UUID submissionId) {
        return ApiResponse.ok(submissions.cancel(chama(), CurrentUser.id(), submissionId), "Report withdrawn");
    }

    // ---- officials ------------------------------------------------------------------------------

    @Operation(summary = "Review queue",
            description = """
                    Reported payments of the kinds I may review. Pending ones first (oldest first, so the queue is \
                    worked in order), then reviewed and withdrawn ones newest first.""")
    @GetMapping
    @PreAuthorize(ANY_REVIEWER)
    ApiResponse<List<SubmissionView>> queue(@RequestParam(required = false) PaymentSubmission.Status status,
                                            @RequestParam(required = false) PaymentSubmission.Kind kind,
                                            @RequestParam(required = false) UUID memberUserId,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.page(submissions.reviewQueue(chama(), CurrentUser.id(), status, kind, memberUserId,
                pageable(page, size)));
    }

    @Operation(summary = "The kinds of payment I may review")
    @GetMapping("/reviewable-kinds")
    @PreAuthorize(ANY_REVIEWER)
    ApiResponse<List<PaymentSubmission.Kind>> reviewableKinds() {
        return ApiResponse.ok(submissions.reviewableKinds(chama(), CurrentUser.id()));
    }

    @Operation(summary = "One reported payment", description = "To its submitter, or an official who may review its kind.")
    @GetMapping("/{submissionId}")
    @RequiresChamaMembership
    ApiResponse<SubmissionView> one(@PathVariable UUID submissionId) {
        return ApiResponse.ok(submissions.submission(chama(), CurrentUser.id(), submissionId));
    }

    @Operation(summary = "Confirm a reported payment",
            description = """
                    Records it as a real contribution, penalty payment or loan repayment under my name, with every \
                    rule of a hand-entered payment (unique reference, no overpayment, not my own money). The \
                    member is told by email.""")
    @PostMapping("/{submissionId}/approve")
    @PreAuthorize(ANY_REVIEWER)
    ApiResponse<SubmissionView> approve(@PathVariable UUID submissionId,
                                        @Valid @RequestBody(required = false) ApproveSubmissionRequest request) {
        return ApiResponse.ok(submissions.approve(chama(), CurrentUser.id(), submissionId,
                request == null ? null : request.note()), "Payment confirmed and recorded");
    }

    @Operation(summary = "Reject a reported payment", description = "With a reason, which the member is emailed.")
    @PostMapping("/{submissionId}/reject")
    @PreAuthorize(ANY_REVIEWER)
    ApiResponse<SubmissionView> reject(@PathVariable UUID submissionId,
                                       @Valid @RequestBody RejectSubmissionRequest request) {
        return ApiResponse.ok(submissions.reject(chama(), CurrentUser.id(), submissionId, request.reason()),
                "Payment rejected");
    }

    private static PageRequest pageable(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
    }

    private static UUID chama() {
        return TenantContext.requireChama();
    }
}
