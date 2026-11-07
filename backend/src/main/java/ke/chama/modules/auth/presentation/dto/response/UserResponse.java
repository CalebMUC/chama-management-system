package ke.chama.modules.auth.presentation.dto.response;

import ke.chama.shared.tenancy.TenantContext;
import ke.chama.modules.auth.application.port.in.UserProfile;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * @param chamaId   the chama the account belongs to; null for platform administrators
 * @param chamaCode its code, as sent in {@code X-Chama-Code}; null for platform administrators
 */
public record UserResponse(UUID id,
                           UUID chamaId,
                           String chamaCode,
                           String email,
                           boolean emailVerified,
                           String phoneNumber,
                           String firstName,
                           String lastName,
                           Set<String> roles,
                           Instant createdAt) {

    public static UserResponse from(UserProfile profile) {
        boolean platform = TenantContext.PLATFORM.equals(profile.tenantId());
        return new UserResponse(profile.id(), platform ? null : profile.tenantId(),
                platform ? null : TenantContext.code().orElse(null), profile.email(), profile.emailVerified(), profile.phoneNumber(),
                profile.firstName(), profile.lastName(), profile.roles(), profile.createdAt());
    }
}
