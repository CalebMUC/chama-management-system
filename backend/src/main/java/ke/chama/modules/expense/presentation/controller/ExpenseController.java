package ke.chama.modules.expense.presentation.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import ke.chama.modules.expense.application.port.in.ExpenseUseCases;
import ke.chama.modules.expense.application.port.in.ExpenseUseCases.ExpenseView;
import ke.chama.modules.expense.application.port.in.ExpenseUseCases.SummaryView;
import ke.chama.modules.expense.domain.model.Expense;
import ke.chama.modules.expense.presentation.dto.request.ExpenseRequests.ApproveRequest;
import ke.chama.modules.expense.presentation.dto.request.ExpenseRequests.ExpenseRequest;
import ke.chama.modules.expense.presentation.dto.request.ExpenseRequests.PayRequest;
import ke.chama.modules.expense.presentation.dto.request.ExpenseRequests.RejectRequest;
import ke.chama.shared.security.ChamaPermission;
import ke.chama.shared.security.CurrentUser;
import ke.chama.shared.security.RequiresChamaPermission;
import ke.chama.shared.tenancy.LicenseFeature;
import ke.chama.shared.tenancy.RequiresFeature;
import ke.chama.shared.tenancy.TenantContext;
import ke.chama.shared.web.ApiResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Expenses of the caller's chama. Needs the EXPENSES licence feature. Every member can see them.
 * <pre>
 * POST /api/v1/expenses                 EXPENSE_CREATE   {amountMinor, payee, description, accountCode, incurredOn}
 * GET  /api/v1/expenses                 EXPENSE_VIEW     ?status=&from=&to=&page=&size=
 * GET  /api/v1/expenses/summary         EXPENSE_VIEW     ?from=&to= (default: this month)
 * GET  /api/v1/expenses/{id}            EXPENSE_VIEW
 * POST /api/v1/expenses/{id}/approve    EXPENSE_APPROVE  {note}
 * POST /api/v1/expenses/{id}/reject     EXPENSE_APPROVE  {reason}
 * POST /api/v1/expenses/{id}/cancel     EXPENSE_CREATE   whoever recorded it
 * POST /api/v1/expenses/{id}/pay        EXPENSE_CREATE   {method, reference, paidOn}
 * </pre>
 */
@Tag(name = "Expenses", description = "Chama spending with maker-checker approval, paid through the ledger")
@RequiresFeature(LicenseFeature.EXPENSES)
@RestController
@RequestMapping("/api/v1/expenses")
class ExpenseController {

    private static final int MAX_PAGE_SIZE = 100;

    private final ExpenseUseCases expenses;

    ExpenseController(ExpenseUseCases expenses) {
        this.expenses = expenses;
    }

    @Operation(summary = "Record an expense",
            description = "Awaits approval by the chama's quorum of officials other than you. Charged to an expense "
                    + "account (default 5000 Operating expenses).")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresChamaPermission(ChamaPermission.EXPENSE_CREATE)
    ApiResponse<ExpenseView> create(@Valid @RequestBody ExpenseRequest request) {
        return ApiResponse.ok(expenses.request(chama(), CurrentUser.id(), request.toCommand()), "Expense recorded for approval");
    }

    @Operation(summary = "List expenses (newest first)", description = "from/to filter on the date the expense was incurred.")
    @GetMapping
    @RequiresChamaPermission(ChamaPermission.EXPENSE_VIEW)
    ApiResponse<List<ExpenseView>> list(@RequestParam(required = false) Expense.Status status,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.page(expenses.expenses(chama(), status, from, to,
                PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE))));
    }

    @Operation(summary = "Spending summary",
            description = "What was paid out between two dates (by payment date), per expense account, and what still "
                    + "awaits payment. Defaults to this month.")
    @GetMapping("/summary")
    @RequiresChamaPermission(ChamaPermission.EXPENSE_VIEW)
    ApiResponse<SummaryView> summary(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(expenses.summary(chama(), from, to));
    }

    @Operation(summary = "One expense", description = "With its approvals and payment.")
    @GetMapping("/{expenseId}")
    @RequiresChamaPermission(ChamaPermission.EXPENSE_VIEW)
    ApiResponse<ExpenseView> get(@PathVariable UUID expenseId) {
        return ApiResponse.ok(expenses.expense(chama(), expenseId));
    }

    @Operation(summary = "Approve an expense",
            description = "One official's approval; approved once the quorum of distinct officials approve. "
                    + "Whoever recorded it cannot approve it.")
    @PostMapping("/{expenseId}/approve")
    @RequiresChamaPermission(ChamaPermission.EXPENSE_APPROVE)
    ApiResponse<ExpenseView> approve(@PathVariable UUID expenseId, @Valid @RequestBody(required = false) ApproveRequest request) {
        ExpenseView expense = expenses.approve(chama(), CurrentUser.id(), expenseId, request == null ? null : request.note());
        return ApiResponse.ok(expense, expense.status() == Expense.Status.APPROVED ? "Expense approved"
                : "Approval recorded (" + expense.approvals().size() + " of " + expense.requiredApprovals() + ")");
    }

    @Operation(summary = "Reject an expense", description = "Before it is paid. Needs a reason.")
    @PostMapping("/{expenseId}/reject")
    @RequiresChamaPermission(ChamaPermission.EXPENSE_APPROVE)
    ApiResponse<ExpenseView> reject(@PathVariable UUID expenseId, @Valid @RequestBody RejectRequest request) {
        return ApiResponse.ok(expenses.reject(chama(), CurrentUser.id(), expenseId, request.reason()), "Expense rejected");
    }

    @Operation(summary = "Cancel an expense", description = "Only whoever recorded it, before it is paid.")
    @PostMapping("/{expenseId}/cancel")
    @RequiresChamaPermission(ChamaPermission.EXPENSE_CREATE)
    ApiResponse<ExpenseView> cancel(@PathVariable UUID expenseId) {
        return ApiResponse.ok(expenses.cancel(chama(), CurrentUser.id(), expenseId), "Expense cancelled");
    }

    @Operation(summary = "Pay an approved expense",
            description = "Posts it to the ledger. The account it is paid from must hold enough.")
    @PostMapping("/{expenseId}/pay")
    @RequiresChamaPermission(ChamaPermission.EXPENSE_CREATE)
    ApiResponse<ExpenseView> pay(@PathVariable UUID expenseId, @Valid @RequestBody PayRequest request) {
        return ApiResponse.ok(expenses.pay(chama(), CurrentUser.id(), expenseId, request.toCommand()), "Expense paid");
    }

    private static UUID chama() {
        return TenantContext.requireChama();
    }
}
