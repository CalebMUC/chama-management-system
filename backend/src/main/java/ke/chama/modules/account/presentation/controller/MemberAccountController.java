package ke.chama.modules.account.presentation.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import ke.chama.modules.account.application.port.in.AccountViews.AccountView;
import ke.chama.modules.account.application.port.in.AccountViews.MyAccountsView;
import ke.chama.modules.account.application.port.in.AccountViews.StatementView;
import ke.chama.modules.account.application.port.in.MemberAccountUseCases;
import ke.chama.modules.account.domain.model.MemberAccount;
import ke.chama.modules.account.presentation.dto.request.AccountRequests.CloseAccountRequest;
import ke.chama.modules.account.presentation.dto.request.AccountRequests.OpenAccountRequest;
import ke.chama.shared.security.ChamaPermission;
import ke.chama.shared.security.CurrentUser;
import ke.chama.shared.security.RequiresChamaMembership;
import ke.chama.shared.security.RequiresChamaPermission;
import ke.chama.shared.tenancy.LicenseFeature;
import ke.chama.shared.tenancy.RequiresFeature;
import ke.chama.shared.tenancy.TenantContext;
import ke.chama.shared.web.ApiResponse;
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
 * Members' accounts in the savings products, with balances from the ledger. Needs the
 * CONTRIBUTIONS licence feature.
 * <pre>
 * GET  /api/v1/member-accounts                       MEMBER_ACCOUNTS_VIEW_ALL  ?memberUserId=&productId=&status=
 * GET  /api/v1/member-accounts/{id}                  MEMBER_ACCOUNTS_VIEW_ALL
 * GET  /api/v1/member-accounts/{id}/statement        MEMBER_ACCOUNTS_VIEW_ALL  ?from=&to=
 * GET  /api/v1/member-accounts/me                    active member             own accounts and balances
 * GET  /api/v1/member-accounts/me/{id}/statement     active member             ?from=&to=
 * POST /api/v1/member-accounts                       MEMBER_ACCOUNTS_MANAGE    {memberUserId, productId}
 * POST /api/v1/member-accounts/{id}/close            MEMBER_ACCOUNTS_MANAGE    {reason}; zero balance only
 * POST /api/v1/member-accounts/{id}/reopen           MEMBER_ACCOUNTS_MANAGE
 * </pre>
 */
@Tag(name = "Member accounts", description = "Each member's account in each savings product, with balances and statements")
@RequiresFeature(LicenseFeature.CONTRIBUTIONS)
@RestController
@RequestMapping("/api/v1/member-accounts")
class MemberAccountController {

    private final MemberAccountUseCases accounts;

    MemberAccountController(MemberAccountUseCases accounts) {
        this.accounts = accounts;
    }

    @Operation(summary = "List member accounts", description = "With each account's balance, by account number.")
    @GetMapping
    @RequiresChamaPermission(ChamaPermission.MEMBER_ACCOUNTS_VIEW_ALL)
    ApiResponse<List<AccountView>> list(@RequestParam(required = false) UUID memberUserId,
                                        @RequestParam(required = false) UUID productId,
                                        @RequestParam(required = false) MemberAccount.Status status) {
        return ApiResponse.ok(accounts.accounts(chama(), memberUserId, productId, status));
    }

    @Operation(summary = "My accounts", description = "Your account in every savings product, with balances.")
    @GetMapping("/me")
    @RequiresChamaMembership
    ApiResponse<MyAccountsView> mine() {
        return ApiResponse.ok(accounts.mine(chama(), CurrentUser.id()));
    }

    @Operation(summary = "My account's statement", description = "Every movement, oldest first, with a running balance.")
    @GetMapping("/me/{accountId}/statement")
    @RequiresChamaMembership
    ApiResponse<StatementView> myStatement(@PathVariable UUID accountId,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(accounts.myStatement(chama(), CurrentUser.id(), accountId, from, to));
    }

    @Operation(summary = "One member account")
    @GetMapping("/{accountId}")
    @RequiresChamaPermission(ChamaPermission.MEMBER_ACCOUNTS_VIEW_ALL)
    ApiResponse<AccountView> get(@PathVariable UUID accountId) {
        return ApiResponse.ok(accounts.account(chama(), accountId));
    }

    @Operation(summary = "A member account's statement", description = "Every movement, oldest first, with a running balance.")
    @GetMapping("/{accountId}/statement")
    @RequiresChamaPermission(ChamaPermission.MEMBER_ACCOUNTS_VIEW_ALL)
    ApiResponse<StatementView> statement(@PathVariable UUID accountId,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(accounts.statement(chama(), accountId, from, to));
    }

    @Operation(summary = "Open an account",
            description = "For products members join by choice. Accounts in mandatory products open by themselves.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresChamaPermission(ChamaPermission.MEMBER_ACCOUNTS_MANAGE)
    ApiResponse<AccountView> open(@Valid @RequestBody OpenAccountRequest request) {
        return ApiResponse.ok(accounts.open(chama(), CurrentUser.id(), request.memberUserId(), request.productId()),
                "Account opened");
    }

    @Operation(summary = "Close an account",
            description = "Only with a zero balance, and not a mandatory product's account while the member is in the chama. "
                    + "No new dues; anything already owed stays owed.")
    @PostMapping("/{accountId}/close")
    @RequiresChamaPermission(ChamaPermission.MEMBER_ACCOUNTS_MANAGE)
    ApiResponse<AccountView> close(@PathVariable UUID accountId, @Valid @RequestBody CloseAccountRequest request) {
        return ApiResponse.ok(accounts.close(chama(), CurrentUser.id(), accountId, request.reason()), "Account closed");
    }

    @Operation(summary = "Reopen a closed account", description = "Dues start again from today.")
    @PostMapping("/{accountId}/reopen")
    @RequiresChamaPermission(ChamaPermission.MEMBER_ACCOUNTS_MANAGE)
    ApiResponse<AccountView> reopen(@PathVariable UUID accountId) {
        return ApiResponse.ok(accounts.reopen(chama(), CurrentUser.id(), accountId), "Account reopened");
    }

    private static UUID chama() {
        return TenantContext.requireChama();
    }
}
