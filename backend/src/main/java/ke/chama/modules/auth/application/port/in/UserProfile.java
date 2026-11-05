package ke.chama.modules.auth.application.port.in;

import ke.chama.modules.auth.domain.model.Role;
import ke.chama.modules.auth.domain.model.User;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Read model of a user, so entities never leave the application layer. */
public record UserProfile(UUID id,
                          String email,
                          String phoneNumber,
                          String firstName,
                          String lastName,
                          Set<String> roles,
                          Instant createdAt) {

    public static UserProfile from(User user) {
        return new UserProfile(user.getId(), user.getEmail(), user.getPhoneNumber(),
                user.getFirstName(), user.getLastName(),
                user.getRoles().stream().map(Role::getName).collect(Collectors.toUnmodifiableSet()),
                user.getCreatedAt());
    }
}
