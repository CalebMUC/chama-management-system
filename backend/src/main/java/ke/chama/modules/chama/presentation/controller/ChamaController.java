package ke.chama.modules.chama.presentation.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import ke.chama.modules.chama.application.port.in.ChamaUseCases;
import ke.chama.modules.chama.application.port.in.LicenseView;
import ke.chama.modules.chama.presentation.dto.request.ChamaSettingsRequest;
import ke.chama.modules.chama.presentation.dto.response.ChamaResponse;
import ke.chama.shared.security.ChamaPermission;
import ke.chama.shared.security.CurrentUser;
import ke.chama.shared.security.RequiresChamaPermission;
import ke.chama.shared.tenancy.TenantContext;
import ke.chama.shared.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The caller's own chama.
 * <pre>
 * GET /api/v1/chama              CHAMA_VIEW
 * PUT /api/v1/chama/settings     CHAMA_SETTINGS_MANAGE
 * GET /api/v1/chama/license      CHAMA_VIEW           plan, validity, member limit, features
 * </pre>
 * There is no chama id in the URLs: a user belongs to exactly one chama, taken from their token.
 * Registering a chama: {@link ChamaRegistrationController}.
 */
@Tag(name = "My chama", description = "Your chama, its settings and its licence")
@RestController
@RequestMapping("/api/v1/chama")
class ChamaController {

    private final ChamaUseCases chamas;

    ChamaController(ChamaUseCases chamas) {
        this.chamas = chamas;
    }

    @Operation(summary = "Get your chama")
    @GetMapping
    @RequiresChamaPermission(ChamaPermission.CHAMA_VIEW)
    ApiResponse<ChamaResponse> get() {
        return ApiResponse.ok(ChamaResponse.from(chamas.get(chama())));
    }

    @Operation(summary = "Change your chama's rules", description = "Contributions, penalties, loans and approval quorum. Audit-logged with before and after values.")
    @PutMapping("/settings")
    @RequiresChamaPermission(ChamaPermission.CHAMA_SETTINGS_MANAGE)
    ApiResponse<ChamaResponse> changeSettings(@Valid @RequestBody ChamaSettingsRequest request) {
        return ApiResponse.ok(ChamaResponse.from(chamas.changeSettings(chama(), CurrentUser.id(), request.toSettings())),
                "Settings updated");
    }

    @Operation(summary = "Get your chama's licence", description = "Works even when the licence has expired, so members can see why the chama is blocked.")
    @GetMapping("/license")
    @RequiresChamaPermission(ChamaPermission.CHAMA_VIEW)
    ApiResponse<LicenseView> license() {
        return ApiResponse.ok(chamas.currentLicense(chama()));
    }

    private static UUID chama() {
        return TenantContext.requireChama();
    }
}
