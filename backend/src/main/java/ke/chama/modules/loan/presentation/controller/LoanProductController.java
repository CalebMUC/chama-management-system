package ke.chama.modules.loan.presentation.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import ke.chama.modules.loan.application.port.in.LoanProductUseCases;
import ke.chama.modules.loan.application.port.in.LoanProductUseCases.LoanProductView;
import ke.chama.modules.loan.presentation.dto.request.LoanRequests.LoanProductRequest;
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
 * Loan products of the caller's chama. Needs the LOANS licence feature.
 * <pre>
 * GET  /api/v1/loan-products                    active member     every product, active first
 * GET  /api/v1/loan-products/{id}               active member
 * POST /api/v1/loan-products                    PRODUCTS_MANAGE   add a product
 * PUT  /api/v1/loan-products/{id}               PRODUCTS_MANAGE   change its terms (for new applications)
 * POST /api/v1/loan-products/{id}/deactivate    PRODUCTS_MANAGE   stop offering it; existing loans carry on
 * POST /api/v1/loan-products/{id}/activate      PRODUCTS_MANAGE
 * </pre>
 */
@Tag(name = "Loan products", description = "Normal, emergency and other kinds of loan, each with its own terms")
@RequiresFeature(LicenseFeature.LOANS)
@RestController
@RequestMapping("/api/v1/loan-products")
class LoanProductController {

    private final LoanProductUseCases products;

    LoanProductController(LoanProductUseCases products) {
        this.products = products;
    }

    @Operation(summary = "List loan products", description = "Active first; the default product follows the chama's loan settings.")
    @GetMapping
    @RequiresChamaMembership
    ApiResponse<List<LoanProductView>> list() {
        return ApiResponse.ok(products.products(chama()));
    }

    @Operation(summary = "One loan product")
    @GetMapping("/{productId}")
    @RequiresChamaMembership
    ApiResponse<LoanProductView> get(@PathVariable UUID productId) {
        return ApiResponse.ok(products.product(chama(), productId));
    }

    @Operation(summary = "Add a loan product",
            description = "A member may have one loan in progress per product; all their loans together stay within "
                    + "each product's multiple of their loan-backing savings.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresChamaPermission(ChamaPermission.PRODUCTS_MANAGE)
    ApiResponse<LoanProductView> create(@Valid @RequestBody LoanProductRequest request) {
        return ApiResponse.ok(products.create(chama(), CurrentUser.id(), request.code(), request.terms()),
                "Loan product created");
    }

    @Operation(summary = "Change a loan product's terms",
            description = "For loans applied for from now on. The default product's terms are the chama's loan "
                    + "settings: only its name can be changed here.")
    @PutMapping("/{productId}")
    @RequiresChamaPermission(ChamaPermission.PRODUCTS_MANAGE)
    ApiResponse<LoanProductView> update(@PathVariable UUID productId, @Valid @RequestBody LoanProductRequest request) {
        return ApiResponse.ok(products.update(chama(), CurrentUser.id(), productId, request.terms()),
                "Loan product updated");
    }

    @Operation(summary = "Stop offering a loan product", description = "Loans already taken carry on. Not the default product.")
    @PostMapping("/{productId}/deactivate")
    @RequiresChamaPermission(ChamaPermission.PRODUCTS_MANAGE)
    ApiResponse<LoanProductView> deactivate(@PathVariable UUID productId) {
        return ApiResponse.ok(products.setActive(chama(), CurrentUser.id(), productId, false), "Loan product deactivated");
    }

    @Operation(summary = "Offer a loan product again")
    @PostMapping("/{productId}/activate")
    @RequiresChamaPermission(ChamaPermission.PRODUCTS_MANAGE)
    ApiResponse<LoanProductView> activate(@PathVariable UUID productId) {
        return ApiResponse.ok(products.setActive(chama(), CurrentUser.id(), productId, true), "Loan product activated");
    }

    private static UUID chama() {
        return TenantContext.requireChama();
    }
}
