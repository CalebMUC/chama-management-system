package ke.chama.modules.account.presentation.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import ke.chama.modules.account.application.port.in.AccountViews.ProductView;
import ke.chama.modules.account.application.port.in.SavingsProductUseCases;
import ke.chama.modules.account.presentation.dto.request.AccountRequests.CreateProductRequest;
import ke.chama.modules.account.presentation.dto.request.AccountRequests.UpdateProductRequest;
import ke.chama.shared.security.ChamaPermission;
import ke.chama.shared.security.CurrentUser;
import ke.chama.shared.security.RequiresChamaMembership;
import ke.chama.shared.security.RequiresChamaPermission;
import ke.chama.shared.tenancy.LicenseFeature;
import ke.chama.shared.tenancy.RequiresFeature;
import ke.chama.shared.tenancy.TenantContext;
import ke.chama.shared.web.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Savings products of the caller's chama. Needs the CONTRIBUTIONS licence feature.
 * <pre>
 * GET  /api/v1/savings-products                    active member      every product, with members and total balance
 * GET  /api/v1/savings-products/{id}               active member
 * POST /api/v1/savings-products                    PRODUCTS_MANAGE    add a product (and its ledger account)
 * PUT  /api/v1/savings-products/{id}               PRODUCTS_MANAGE    change its terms (for periods opened from now on)
 * POST /api/v1/savings-products/{id}/deactivate    PRODUCTS_MANAGE    stop asking for it; balances stay
 * POST /api/v1/savings-products/{id}/activate      PRODUCTS_MANAGE
 * </pre>
 */
@Tag(name = "Savings products", description = "Deposits, share capital, welfare and other pots members save into")
@RequiresFeature(LicenseFeature.CONTRIBUTIONS)
@RestController
@RequestMapping("/api/v1/savings-products")
class SavingsProductController {

    private final SavingsProductUseCases products;

    SavingsProductController(SavingsProductUseCases products) {
        this.products = products;
    }

    @Operation(summary = "List savings products", description = "Active products first, by allocation priority.")
    @GetMapping
    @RequiresChamaMembership
    ApiResponse<List<ProductView>> list() {
        return ApiResponse.ok(products.listProducts(chama()));
    }

    @Operation(summary = "One savings product")
    @GetMapping("/{productId}")
    @RequiresChamaMembership
    ApiResponse<ProductView> get(@PathVariable UUID productId) {
        return ApiResponse.ok(products.product(chama(), productId));
    }

    @Operation(summary = "Add a savings product",
            description = """
                    Creates the product and its own member-ledger account (2010, 2020, ... or 3100 for share \
                    capital, unless ledgerAccountCode is given). A mandatory product opens an account for every \
                    member at once, and every member owes contributionMinor per contribution period from the \
                    next period on. Within a period, a payment clears products with a lower allocationPriority \
                    first; whatever is left goes to the default product (Deposits).""")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresChamaPermission(ChamaPermission.PRODUCTS_MANAGE)
    ApiResponse<ProductView> create(@Valid @RequestBody CreateProductRequest request) {
        return ApiResponse.ok(products.create(chama(), CurrentUser.id(), request.code(), request.kind(),
                request.ledgerAccountCode(), request.terms()), "Savings product created");
    }

    @Operation(summary = "Change a savings product's terms",
            description = """
                    Applies to contribution periods opened from now on. The default product's contribution is the \
                    chama's contribution amount: change it in the chama's settings.""")
    @PutMapping("/{productId}")
    @RequiresChamaPermission(ChamaPermission.PRODUCTS_MANAGE)
    ApiResponse<ProductView> update(@PathVariable UUID productId, @Valid @RequestBody UpdateProductRequest request) {
        return ApiResponse.ok(products.update(chama(), CurrentUser.id(), productId, request.terms()),
                "Savings product updated");
    }

    @Operation(summary = "Deactivate a savings product",
            description = "New periods stop asking for it and it takes no new payments. Balances stay. Not the default product.")
    @PostMapping("/{productId}/deactivate")
    @RequiresChamaPermission(ChamaPermission.PRODUCTS_MANAGE)
    ApiResponse<ProductView> deactivate(@PathVariable UUID productId) {
        return ApiResponse.ok(products.setActive(chama(), CurrentUser.id(), productId, false), "Savings product deactivated");
    }

    @Operation(summary = "Activate a savings product")
    @PostMapping("/{productId}/activate")
    @RequiresChamaPermission(ChamaPermission.PRODUCTS_MANAGE)
    ApiResponse<ProductView> activate(@PathVariable UUID productId) {
        return ApiResponse.ok(products.setActive(chama(), CurrentUser.id(), productId, true), "Savings product activated");
    }

    private static UUID chama() {
        return TenantContext.requireChama();
    }
}
