package ke.chama.modules.auth.presentation.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import ke.chama.modules.auth.application.port.in.RegisterUserCommand;

public record RegisterRequest(
        @NotBlank @Email @Size(max = 255)
        String email,

        /* Normalized to +254... by the domain; format errors return INVALID_PHONE_NUMBER. */
        @NotBlank @Size(max = 20)
        String phoneNumber,

        @NotBlank @Size(min = 8, max = 72)
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "must contain at least one letter and one digit")
        String password,

        @NotBlank @Size(max = 100)
        String firstName,

        @NotBlank @Size(max = 100)
        String lastName) {

    /** Runs before validation: trailing spaces from phone keyboards would otherwise fail @Email. */
    public RegisterRequest {
        email = email == null ? null : email.trim();
    }

    public RegisterUserCommand toCommand() {
        return new RegisterUserCommand(email, phoneNumber, password, firstName, lastName);
    }

    /** Never print the password, e.g. if this record ends up in a log line. */
    @Override
    public String toString() {
        return "RegisterRequest[email=" + email + ", phoneNumber=" + phoneNumber + ", password=***]";
    }
}
