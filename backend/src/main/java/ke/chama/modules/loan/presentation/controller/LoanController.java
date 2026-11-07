package ke.chama.modules.loan.presentation.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import ke.chama.modules.loan.application.port.in.LoanUseCases;
import ke.chama.modules.loan.application.port.in.LoanUseCases.EligibilityView;
import ke.chama.modules.loan.application.port.in.LoanUseCases.GuaranteeView;
import ke.chama.modules.loan.application.port.in.LoanUseCases.LoanDetailView;
import ke.chama.modules.loan.application.port.in.LoanUseCases.LoanView;
import ke.chama.modules.loan.application.port.in.LoanUseCases.MyLoansView;
import ke.chama.modules.loan.domain.model.Loan;
import ke.chama.modules.loan.presentation.dto.request.LoanRequests.AddGuarantorRequest;
import ke.chama.modules.loan.presentation.dto.request.LoanRequests.ApplyRequest;
import ke.chama.modules.loan.presentation.dto.request.LoanRequests.ApproveRequest;
import ke.chama.modules.loan.presentation.dto.request.LoanRequests.DisburseRequest;
import ke.chama.modules.loan.presentation.dto.request.LoanRequests.GuaranteeResponse;
import ke.chama.modules.loan.presentation.dto.request.LoanRequests.RejectRequest;
import ke.chama.modules.loan.presentation.dto.request.LoanRequests.RepaymentRequest;
import ke.chama.shared.security.ChamaPermission;
import ke.chama.shared.security.CurrentUser;
import ke.chama.shared.security.RequiresChamaMembership;
import ke.chama.shared.security.RequiresChamaPermission;
import ke.chama.shared.tenancy.LicenseFeature;
import ke.chama.shared.tenancy.RequiresFeature;
import ke.chama.shared.tenancy.TenantContext;
import ke.chama.shared.web.ApiResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
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
 * Loans of the caller's chama. Needs the LOANS licence feature.
 * <pre>
 * GET  /api/v1/loans/eligibility        LOAN_APPLY             what I may borrow now
 * POST /api/v1/loans                    LOAN_APPLY             {principalMinor, termMonths, purpose, guarantorUserIds}
 * GET  /api/v1/loans/me                 active member          my loans, with schedules
 * GET  /api/v1/loans/guarantees         active member          loans I was asked to guarantee
 * POST /api/v1/loans/{id}/guarantee     active member          {accept} - the guarantor's answer
 * POST /api/v1/loans/{id}/guarantors    active member          {guarantorUserId} - the borrower names another
 * POST /api/v1/loans/{id}/cancel        active member          the borrower withdraws, before disbursement
 * GET  /api/v1/loans                    LOAN_VIEW_ALL          ?status=&memberUserId=&page=&size=
 * GET  /api/v1/loans/{id}               LOAN_VIEW_ALL
 * POST /api/v1/loans/{id}/approve       LOAN_APPROVE           {note}
 * POST /api/v1/loans/{id}/reject        LOAN_APPROVE           {reason}
 * POST /api/v1/loans/{id}/disburse      LOAN_DISBURSE          {method, reference, disbursedOn}
 * POST /api/v1/loans/{id}/repayments    LOAN_REPAYMENT_RECORD  {amountMinor, method, reference, paidOn}
 * </pre>
 */
@Tag(name = "Loans", description = "Applications, guarantors, approval by quorum, disbursement and repayments")
@RequiresFeature(LicenseFeature.LOANS)
@RestController
@RequestMapping("/api/v1/loans")
class LoanController {

    private static final int MAX_PAGE_SIZE = 100;

    private final LoanUseCases loans;

    LoanController(LoanUseCases loans) {
        this.loans = loans;
    }

    // ---- members --------------------------------------------------------------------------------

    @Operation(summary = "What I may borrow now",
            description = "The limit (a multiple of my savings), interest, guarantor rules, and any reasons I cannot borrow yet.")
    @GetMapping("/eligibility")
    @RequiresChamaPermission(ChamaPermission.LOAN_APPLY)
    ApiResponse<EligibilityView> eligibility() {
        return ApiResponse.ok(loans.eligibility(chama(), CurrentUser.id()));
    }

    @Operation(summary = "Apply for a loan",
            description = "Loans above the guarantor threshold must name guarantors, who are asked by email. "
                    + "Then a quorum of officials must approve.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresChamaPermission(ChamaPermission.LOAN_APPLY)
    ApiResponse<LoanDetailView> apply(@Valid @RequestBody ApplyRequest request) {
        return ApiResponse.ok(loans.apply(chama(), CurrentUser.id(), request.toCommand()), "Loan application submitted");
    }

    @Operation(summary = "My loans", description = "Newest first, each with its repayment schedule and repayments.")
    @GetMapping("/me")
    @RequiresChamaMembership
    ApiResponse<MyLoansView> mine() {
        return ApiResponse.ok(loans.mine(chama(), CurrentUser.id()));
    }

    @Operation(summary = "Loans I was asked to guarantee")
    @GetMapping("/guarantees")
    @RequiresChamaMembership
    ApiResponse<List<GuaranteeView>> guarantees() {
        return ApiResponse.ok(loans.guaranteeRequests(chama(), CurrentUser.id()));
    }

    @Operation(summary = "Accept or decline guaranteeing a loan",
            description = "Once enough guarantors accept, the loan goes to the officials for approval.")
    @PostMapping("/{loanId}/guarantee")
    @RequiresChamaMembership
    ApiResponse<LoanDetailView> guarantee(@PathVariable UUID loanId, @Valid @RequestBody GuaranteeResponse request) {
        boolean accept = request.accept();
        return ApiResponse.ok(loans.respondAsGuarantor(chama(), CurrentUser.id(), loanId, accept),
                accept ? "You are now a guarantor of this loan" : "You declined to guarantee this loan");
    }

    @Operation(summary = "Name another guarantor", description = "Borrower only, while the loan still needs guarantors.")
    @PostMapping("/{loanId}/guarantors")
    @RequiresChamaMembership
    ApiResponse<LoanDetailView> addGuarantor(@PathVariable UUID loanId, @Valid @RequestBody AddGuarantorRequest request) {
        return ApiResponse.ok(loans.addGuarantor(chama(), CurrentUser.id(), loanId, request.guarantorUserId()),
                "Guarantor asked");
    }

    @Operation(summary = "Cancel my application", description = "Borrower only, before the money is disbursed.")
    @PostMapping("/{loanId}/cancel")
    @RequiresChamaMembership
    ApiResponse<LoanDetailView> cancel(@PathVariable UUID loanId) {
        return ApiResponse.ok(loans.cancel(chama(), CurrentUser.id(), loanId), "Loan application cancelled");
    }

    // ---- officials ------------------------------------------------------------------------------

    @Operation(summary = "List loans (newest first)")
    @GetMapping
    @RequiresChamaPermission(ChamaPermission.LOAN_VIEW_ALL)
    ApiResponse<List<LoanView>> list(@RequestParam(required = false) UUID memberUserId,
                                     @RequestParam(required = false) Loan.Status status,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.page(loans.loans(chama(), memberUserId, status,
                PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE))));
    }

    @Operation(summary = "One loan", description = "With guarantors, approvals, schedule and repayments.")
    @GetMapping("/{loanId}")
    @RequiresChamaPermission(ChamaPermission.LOAN_VIEW_ALL)
    ApiResponse<LoanDetailView> get(@PathVariable UUID loanId) {
        return ApiResponse.ok(loans.loan(chama(), loanId));
    }

    @Operation(summary = "Approve a loan",
            description = "One official's approval. The loan is approved once the chama's quorum of distinct officials approve. "
                    + "Borrowers and guarantors cannot approve.")
    @PostMapping("/{loanId}/approve")
    @RequiresChamaPermission(ChamaPermission.LOAN_APPROVE)
    ApiResponse<LoanDetailView> approve(@PathVariable UUID loanId, @Valid @RequestBody(required = false) ApproveRequest request) {
        LoanDetailView loan = loans.approve(chama(), CurrentUser.id(), loanId, request == null ? null : request.note());
        return ApiResponse.ok(loan, loan.loan().status() == Loan.Status.APPROVED ? "Loan approved"
                : "Approval recorded (" + loan.loan().approvals() + " of " + loan.loan().requiredApprovals() + ")");
    }

    @Operation(summary = "Reject a loan", description = "Before disbursement. Needs a reason, which the borrower is told.")
    @PostMapping("/{loanId}/reject")
    @RequiresChamaPermission(ChamaPermission.LOAN_APPROVE)
    ApiResponse<LoanDetailView> reject(@PathVariable UUID loanId, @Valid @RequestBody RejectRequest request) {
        return ApiResponse.ok(loans.reject(chama(), CurrentUser.id(), loanId, request.reason()), "Loan rejected");
    }

    @Operation(summary = "Disburse an approved loan",
            description = "Records that the principal was paid out and posts it to the ledger. The account it is paid from "
                    + "must hold enough. Starts the repayment schedule.")
    @PostMapping("/{loanId}/disburse")
    @RequiresChamaPermission(ChamaPermission.LOAN_DISBURSE)
    ApiResponse<LoanDetailView> disburse(@PathVariable UUID loanId, @Valid @RequestBody DisburseRequest request) {
        return ApiResponse.ok(loans.disburse(chama(), CurrentUser.id(), loanId, request.toCommand()), "Loan disbursed");
    }

    @Operation(summary = "Record a repayment", description = "Up to what is outstanding. Clears the oldest instalment first.")
    @PostMapping("/{loanId}/repayments")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresChamaPermission(ChamaPermission.LOAN_REPAYMENT_RECORD)
    ApiResponse<LoanDetailView> repay(@PathVariable UUID loanId, @Valid @RequestBody RepaymentRequest request) {
        LoanDetailView loan = loans.recordRepayment(chama(), CurrentUser.id(), loanId, request.toCommand());
        return ApiResponse.ok(loan, loan.loan().status() == Loan.Status.REPAID ? "Loan fully repaid" : "Repayment recorded");
    }

    private static UUID chama() {
        return TenantContext.requireChama();
    }
}
