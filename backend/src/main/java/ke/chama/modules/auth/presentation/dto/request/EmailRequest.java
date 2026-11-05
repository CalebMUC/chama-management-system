package ke.chama.modules.auth.presentation.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EmailRequest(@NotBlank @Email @Size(max = 255) String email) {

    public EmailRequest {
        email = email == null ? null : email.trim();
    }
}
