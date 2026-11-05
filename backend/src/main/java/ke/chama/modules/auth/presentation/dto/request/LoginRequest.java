package ke.chama.modules.auth.presentation.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import ke.chama.modules.auth.application.port.in.LoginCommand;

public record LoginRequest(
        @NotBlank @Email @Size(max = 255)
        String email,

        @NotBlank @Size(max = 128)
        String password) {

    /** Runs before validation: trailing spaces from phone keyboards would otherwise fail @Email. */
    public LoginRequest {
        email = email == null ? null : email.trim();
    }

    public LoginCommand toCommand() {
        return new LoginCommand(email, password);
    }

    @Override
    public String toString() {
        return "LoginRequest[email=" + email + ", password=***]";
    }
}
