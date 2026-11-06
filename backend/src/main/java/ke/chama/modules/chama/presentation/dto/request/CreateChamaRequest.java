package ke.chama.modules.chama.presentation.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import ke.chama.modules.chama.domain.model.ChamaSettings;

import java.util.Currency;

/** {@code currency} defaults to KES and {@code settings} to {@link ChamaSettings#standard()}. */
public record CreateChamaRequest(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 1000) String description,
        @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO 4217 code such as KES") String currency,
        @Valid ChamaSettingsRequest settings) {

    public Currency currencyOrDefault() {
        return currency == null ? Currency.getInstance("KES") : Currency.getInstance(currency);
    }

    public ChamaSettings settingsOrDefault() {
        return settings == null ? ChamaSettings.standard() : settings.toSettings();
    }
}
