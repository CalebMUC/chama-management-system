package ke.chama.modules.chama.presentation.controller;

import jakarta.validation.Valid;
import ke.chama.modules.chama.application.port.in.ChamaUseCases;
import ke.chama.modules.chama.presentation.dto.request.ChamaSettingsRequest;
import ke.chama.modules.chama.presentation.dto.request.CreateChamaRequest;
import ke.chama.modules.chama.presentation.dto.response.ChamaResponse;
import ke.chama.shared.security.ChamaPermission;
import ke.chama.shared.security.CurrentUser;
import ke.chama.shared.security.RequiresChamaPermission;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * <pre>
 * POST /api/v1/chamas                     verified email   found a chama (caller becomes chairperson)
 * GET  /api/v1/chamas/{chamaId}           CHAMA_VIEW
 * PUT  /api/v1/chamas/{chamaId}/settings  CHAMA_SETTINGS_MANAGE
 * </pre>
 * The chamas I belong to: {@code GET /api/v1/me/chamas} (member module).
 */
@RestController
@RequestMapping("/api/v1/chamas")
class ChamaController {

    private final ChamaUseCases chamas;

    ChamaController(ChamaUseCases chamas) {
        this.chamas = chamas;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ChamaResponse found(@Valid @RequestBody CreateChamaRequest request) {
        return ChamaResponse.from(chamas.found(CurrentUser.id(), request.name(), request.description(),
                request.currencyOrDefault(), request.settingsOrDefault()));
    }

    @GetMapping("/{chamaId}")
    @RequiresChamaPermission(ChamaPermission.CHAMA_VIEW)
    ChamaResponse get(@PathVariable UUID chamaId) {
        return ChamaResponse.from(chamas.get(chamaId));
    }

    @PutMapping("/{chamaId}/settings")
    @RequiresChamaPermission(ChamaPermission.CHAMA_SETTINGS_MANAGE)
    ChamaResponse changeSettings(@PathVariable UUID chamaId, @Valid @RequestBody ChamaSettingsRequest request) {
        return ChamaResponse.from(chamas.changeSettings(chamaId, CurrentUser.id(), request.toSettings()));
    }
}
