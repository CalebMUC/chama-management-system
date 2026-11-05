package ke.chama.modules.auth.presentation.dto.response;

import ke.chama.modules.auth.application.port.in.UserProfile;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record UserResponse(UUID id,
                           String email,
                           String phoneNumber,
                           String firstName,
                           String lastName,
                           Set<String> roles,
                           Instant createdAt) {

    public static UserResponse from(UserProfile profile) {
        return new UserResponse(profile.id(), profile.email(), profile.phoneNumber(),
                profile.firstName(), profile.lastName(), profile.roles(), profile.createdAt());
    }
}
