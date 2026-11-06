package ke.chama.modules.auth.presentation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The {@code token} query parameter from an emailed link, posted back by the frontend. */
public record TokenRequest(@NotBlank @Size(max = 512) String token) {

    @Override
    public String toString() {
        return "TokenRequest[token=***]";
    }
}
