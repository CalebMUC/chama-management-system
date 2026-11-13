package ke.chama.modules.auth.application.port.in;

import ke.chama.modules.auth.domain.model.Role;
import ke.chama.modules.auth.domain.model.User;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Read model of a user, so entities never leave the application layer. */
public record UserProfile(UUID id,
                          UUID tenantId,
                          String email,
                          boolean emailVerified,
                          String phoneNumber,
                          String firstName,
                          String lastName,
                          Set<String> roles,
                          Instant createdAt,
                          boolean mustChangePassword) {

    public static UserProfile from(User user) {
        return new UserProfile(user.getId(), user.getTenantId(), user.getEmail(), user.isEmailVerified(), user.getPhoneNumber(),
                user.getFirstName(), user.getLastName(),
                user.getRoles().stream().map(Role::getName).collect(Collectors.toUnmodifiableSet()),
                user.getCreatedAt(), user.isMustChangePassword());
    }
}
