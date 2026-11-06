package ke.chama.modules.transaction.presentation.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import ke.chama.modules.transaction.application.port.in.LedgerUseCases;
import ke.chama.modules.transaction.application.port.in.LedgerViews.AccountView;
import ke.chama.modules.transaction.application.port.in.LedgerViews.EntryView;
import ke.chama.modules.transaction.application.port.in.LedgerViews.MemberBalancesView;
import ke.chama.modules.transaction.application.port.in.LedgerViews.StatementView;
import ke.chama.modules.transaction.application.port.in.LedgerViews.TrialBalanceView;
import ke.chama.modules.transaction.presentation.dto.request.LedgerRequests.AccountStatusRequest;
import ke.chama.modules.transaction.presentation.dto.request.LedgerRequests.CreateAccountRequest;
import ke.chama.modules.transaction.presentation.dto.request.LedgerRequests.ManualEntryRequest;
import ke.chama.modules.transaction.presentation.dto.request.LedgerRequests.ReverseEntryRequest;
import ke.chama.shared.security.ChamaPermission;
import ke.chama.shared.security.CurrentUser;
import ke.chama.shared.security.RequiresChamaMembership;
import ke.chama.shared.security.RequiresChamaPermission;
import ke.chama.shared.tenancy.TenantContext;
import ke.chama.shared.web.ApiResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The caller's chama's books. Amounts are minor units (cents).
 * <pre>
 * GET  /api/v1/ledger/accounts                       LEDGER_VIEW             chart of accounts with balances
 * POST /api/v1/ledger/accounts                       LEDGER_ACCOUNTS_MANAGE  add an account
 * PUT  /api/v1/ledger/accounts/{id}/status           LEDGER_ACCOUNTS_MANAGE  activate / deactivate
 * GET  /api/v1/ledger/accounts/{id}/statement        LEDGER_VIEW             ?from=&to=&memberUserId=
 * GET  /api/v1/ledger/entries                        LEDGER_VIEW             ?from=&to=&sourceType=&page=&size=
 * GET  /api/v1/ledger/entries/{id}                   LEDGER_VIEW
 * POST /api/v1/ledger/entries                        LEDGER_POST             manual entry
 * POST /api/v1/ledger/entries/{id}/reverse           LEDGER_POST             {reason}
 * GET  /api/v1/ledger/trial-balance                  LEDGER_VIEW             ?asOf=
 * GET  /api/v1/ledger/me                             active member           own savings and loan balances
 * </pre>
 */
@Tag(name = "Ledger", description = "Double-entry books of your chama: accounts, journal, statements, trial balance")
@RestController
@RequestMapping("/api/v1/ledger")
class LedgerController {

    private static final int MAX_PAGE_SIZE = 100;

    private final LedgerUseCases ledger;

    LedgerController(LedgerUseCases ledger) {
        this.ledger = ledger;
    }

    @Operation(summary = "Chart of accounts with balances")
    @GetMapping("/accounts")
    @RequiresChamaPermission(ChamaPermission.LEDGER_VIEW)
    ApiResponse<List<AccountView>> accounts() {
        return ApiResponse.ok(ledger.chart(chama()));
    }

    @Operation(summary = "Add an account to the chart", description = "E.g. a second bank account. Codes are unique in the chama.")
    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresChamaPermission(ChamaPermission.LEDGER_ACCOUNTS_MANAGE)
    ApiResponse<AccountView> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        return ApiResponse.ok(ledger.createAccount(chama(), CurrentUser.id(), request.code(), request.name(), request.type()),
                "Account created");
    }

    @Operation(summary = "Activate or deactivate an account", description = "Inactive accounts cannot be posted to. System accounts cannot be deactivated.")
    @PutMapping("/accounts/{accountId}/status")
    @RequiresChamaPermission(ChamaPermission.LEDGER_ACCOUNTS_MANAGE)
    ApiResponse<AccountView> setAccountStatus(@PathVariable UUID accountId, @Valid @RequestBody AccountStatusRequest request) {
        return ApiResponse.ok(ledger.setAccountActive(chama(), CurrentUser.id(), accountId, request.active()),
                request.active() ? "Account activated" : "Account deactivated");
    }

    @Operation(summary = "Account statement", description = "Every movement with a running balance. memberUserId narrows a member account to one member.")
    @GetMapping("/accounts/{accountId}/statement")
    @RequiresChamaPermission(ChamaPermission.LEDGER_VIEW)
    ApiResponse<StatementView> statement(@PathVariable UUID accountId,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(required = false) UUID memberUserId) {
        return ApiResponse.ok(ledger.statement(chama(), accountId, memberUserId, from, to));
    }

    @Operation(summary = "Journal (newest first)")
    @GetMapping("/entries")
    @RequiresChamaPermission(ChamaPermission.LEDGER_VIEW)
    ApiResponse<List<EntryView>> entries(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(required = false) String sourceType,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.page(ledger.entries(chama(), from, to, sourceType,
                PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE))));
    }

    @Operation(summary = "One journal entry")
    @GetMapping("/entries/{entryId}")
    @RequiresChamaPermission(ChamaPermission.LEDGER_VIEW)
    ApiResponse<EntryView> entry(@PathVariable UUID entryId) {
        return ApiResponse.ok(ledger.entry(chama(), entryId));
    }

    @Operation(summary = "Post a manual entry",
            description = "Opening balances, bank charges, corrections. Debits must equal credits. Audit-logged.")
    @PostMapping("/entries")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresChamaPermission(ChamaPermission.LEDGER_POST)
    ApiResponse<EntryView> post(@Valid @RequestBody ManualEntryRequest request) {
        EntryView entry = ledger.postManual(chama(), CurrentUser.id(), request.toEntry());
        return ApiResponse.ok(entry, "Entry #" + entry.number() + " posted");
    }

    @Operation(summary = "Reverse an entry",
            description = "Posts the mirror image of the entry, dated today. Entries are never edited or deleted.")
    @PostMapping("/entries/{entryId}/reverse")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresChamaPermission(ChamaPermission.LEDGER_POST)
    ApiResponse<EntryView> reverse(@PathVariable UUID entryId, @Valid @RequestBody ReverseEntryRequest request) {
        EntryView reversal = ledger.reverseEntry(chama(), CurrentUser.id(), entryId, request.reason());
        return ApiResponse.ok(reversal, "Reversed as entry #" + reversal.number());
    }

    @Operation(summary = "Trial balance", description = "Net balance of every account; total debits equal total credits.")
    @GetMapping("/trial-balance")
    @RequiresChamaPermission(ChamaPermission.LEDGER_VIEW)
    ApiResponse<TrialBalanceView> trialBalance(@RequestParam(required = false)
                                               @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return ApiResponse.ok(ledger.trialBalance(chama(), asOf));
    }

    @Operation(summary = "My balances", description = "Your savings and the loan balance you owe.")
    @GetMapping("/me")
    @RequiresChamaMembership
    ApiResponse<MemberBalancesView> myBalances() {
        return ApiResponse.ok(ledger.memberBalances(chama(), CurrentUser.id()));
    }

    private static UUID chama() {
        return TenantContext.requireChama();
    }
}
