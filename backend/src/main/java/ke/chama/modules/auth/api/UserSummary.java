package ke.chama.modules.auth.api;

import java.util.UUID;

/** What other modules may know about a user. Never includes credentials. */
public record UserSummary(UUID id,
                          String email,
                          boolean emailVerified,
                          String phoneNumber,
                          String firstName,
                          String lastName,
                          boolean enabled) {

    public String fullName() {
        return firstName + " " + lastName;
    }
}
