package ke.chama.modules.chama.presentation.dto.response;

import ke.chama.modules.chama.application.port.in.ChamaOnboarding.OnboardingResult;
import ke.chama.modules.chama.application.port.in.LicenseView;

import java.util.UUID;

/** The new chama, its licence, and the administrator account (who must verify their email). */
public record OnboardingResponse(ChamaResponse chama, LicenseView license, Admin admin) {

    public record Admin(UUID userId, String email) {
    }

    public static OnboardingResponse from(OnboardingResult result) {
        return new OnboardingResponse(ChamaResponse.from(result.chama()), result.license(),
                new Admin(result.adminUserId(), result.adminEmail()));
    }

    public String successMessage() {
        return "Chama registered. Log in with chama code '" + chama.code()
                + "'; the administrator must verify their email address.";
    }
}
