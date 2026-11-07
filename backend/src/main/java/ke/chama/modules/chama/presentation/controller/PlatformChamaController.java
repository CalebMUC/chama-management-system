package ke.chama.modules.chama.presentation.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import ke.chama.modules.chama.application.port.in.ChamaOnboarding;
import ke.chama.modules.chama.application.port.in.LicenseView;
import ke.chama.modules.chama.application.port.in.PlatformChamaAdmin;
import ke.chama.modules.chama.application.port.in.PlatformChamaAdmin.PlatformChamaView;
import ke.chama.modules.chama.presentation.dto.request.PlatformOnboardChamaRequest;
import ke.chama.modules.chama.presentation.dto.request.UpdateLicenseRequest;
import ke.chama.modules.chama.presentation.dto.response.OnboardingResponse;
import ke.chama.shared.security.CurrentUser;
import ke.chama.shared.web.ApiResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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

import java.util.List;
import java.util.UUID;

/**
 * Platform administrators only (ROLE_ADMIN, enforced in SecurityConfig).
 * <pre>
 * POST /api/v1/platform/chamas                onboard a chama, optionally with its starting licence
 * GET  /api/v1/platform/chamas?page=&size=    every chama with its licence
 * GET  /api/v1/platform/chamas/{id}
 * PUT  /api/v1/platform/chamas/{id}/license   renew, upgrade, suspend, reactivate
 * </pre>
 */
@Tag(name = "Platform", description = "Platform administration: onboard chamas and manage their licences")
@RestController
@RequestMapping("/api/v1/platform/chamas")
class PlatformChamaController {

    private static final int MAX_PAGE_SIZE = 100;

    private final PlatformChamaAdmin platform;
    private final ChamaOnboarding onboarding;

    PlatformChamaController(PlatformChamaAdmin platform, ChamaOnboarding onboarding) {
        this.platform = platform;
        this.onboarding = onboarding;
    }

    @Operation(summary = "Onboard a chama",
            description = """
                    The only way to add a chama. Creates the chama with its unique code, its licence and \
                    its first administrator, who becomes chairperson and member number 1 and is emailed a \
                    verification link. The licence is a 30-day trial unless `license` sets the starting \
                    terms (plan, validity, member limit, features). Members then register with the chama \
                    code and are approved by an official.""")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<OnboardingResponse> onboard(@Valid @RequestBody PlatformOnboardChamaRequest request) {
        OnboardingResponse response = OnboardingResponse.from(onboarding.onboard(
                request.chama().toCommand(request.license() == null ? null : request.license().toChange())));
        return ApiResponse.ok(response, response.successMessage());
    }

    @Operation(summary = "List every chama with its licence")
    @GetMapping
    ApiResponse<List<PlatformChamaView>> list(@RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "50") int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Order.asc("name")));
        return ApiResponse.page(platform.chamas(pageable));
    }

    @Operation(summary = "Get a chama and its licence")
    @GetMapping("/{chamaId}")
    ApiResponse<PlatformChamaView> get(@PathVariable UUID chamaId) {
        return ApiResponse.ok(platform.chama(chamaId));
    }

    @Operation(summary = "Renew, upgrade, suspend or reactivate a licence",
            description = "Only the fields sent change. Recorded in the chama's own audit log.")
    @PutMapping("/{chamaId}/license")
    ApiResponse<LicenseView> updateLicense(@PathVariable UUID chamaId, @Valid @RequestBody UpdateLicenseRequest request) {
        return ApiResponse.ok(platform.updateLicense(chamaId, CurrentUser.id(), request.toChange()), "Licence updated");
    }
}
