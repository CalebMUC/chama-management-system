package ke.chama.shared.security;

import java.util.Set;
import java.util.UUID;

/**
 * The caller behind the current request, taken from the validated access token.
 *
 * @param tenantId      the chama the user belongs to (or the platform tenant for platform admins)
 * @param tenantCode    that chama's code; null for platform admins
 * @param platformRoles global roles such as ROLE_ADMIN. What the user may do inside their chama
 *                      is decided by their membership, not here.
 */
public record AuthenticatedUser(UUID userId, String email, UUID tenantId, String tenantCode,
                                Set<String> platformRoles) {

    public boolean hasPlatformRole(String role) {
        return platformRoles.contains(role);
    }
}
