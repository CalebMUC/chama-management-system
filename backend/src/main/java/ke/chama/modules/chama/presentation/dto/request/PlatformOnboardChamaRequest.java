package ke.chama.modules.chama.presentation.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * A platform administrator onboarding a chama, optionally with its starting licence instead of
 * the standard trial, e.g. {@code "license": {"plan": "STANDARD", "validUntil": "2027-12-31T21:00:00Z"}}.
 */
public record PlatformOnboardChamaRequest(@NotNull @Valid OnboardChamaRequest chama,
                                          @Valid UpdateLicenseRequest license) {
}
