package ke.chama.modules.chama.presentation.dto.request;

import ke.chama.modules.chama.application.port.in.PlatformChamaAdmin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import ke.chama.modules.chama.application.port.in.ChamaOnboarding;
import ke.chama.modules.chama.domain.model.ChamaSettings;

import java.util.Currency;

/**
 * Registers a new chama and its first administrator.
 *
 * @param code     the chama's unique login code, e.g. {@code mokica}: 3 to 30 letters, digits or
 *                 hyphens. Members send it as {@code X-Chama-Code}. It cannot be changed later.
 * @param currency defaults to KES
 * @param settings defaults to {@link ChamaSettings#standard()}
 */
public record OnboardChamaRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(min = 3, max = 30)
        @Pattern(regexp = "[A-Za-z0-9]+(-[A-Za-z0-9]+)*", message = "may contain only letters, digits and single hyphens")
        String code,
        @Size(max = 1000) String description,
        @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO 4217 code such as KES") String currency,
        @Valid ChamaSettingsRequest settings,
        @NotNull @Valid Administrator admin) {

    public OnboardChamaRequest {
        code = code == null ? null : code.trim();
    }

    /** The chama's first chairperson. They log in with the chama code and this email. */
    public record Administrator(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(max = 20) String phoneNumber,
            @NotBlank @Size(min = 8, max = 72)
            @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "must contain at least one letter and one digit")
            String password,
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName) {

        public Administrator {
            email = email == null ? null : email.trim();
        }

        @Override
        public String toString() {
            return "Administrator[email=" + email + ", password=***]";
        }
    }

    public ChamaOnboarding.Command toCommand() {
        return toCommand(null);
    }

    public ChamaOnboarding.Command toCommand(PlatformChamaAdmin.LicenseChange license) {
        return new ChamaOnboarding.Command(name, code, description,
                currency == null ? Currency.getInstance("KES") : Currency.getInstance(currency),
                settings == null ? ChamaSettings.standard() : settings.toSettings(),
                new ChamaOnboarding.Admin(admin.email(), admin.phoneNumber(), admin.password(),
                        admin.firstName(), admin.lastName()), license);
    }
}
