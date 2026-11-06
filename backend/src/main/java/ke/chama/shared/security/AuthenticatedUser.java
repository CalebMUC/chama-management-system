package ke.chama.shared.security;

import java.util.Set;
import java.util.UUID;

/**
 * The caller behind the current request, taken from the validated access token.
 * {@code platformRoles} are global roles such as ROLE_ADMIN. What the user may do inside a
 * particular chama is decided per membership (Phase 2), not here.
 */
public record AuthenticatedUser(UUID userId, String email, Set<String> platformRoles) {

    public boolean hasPlatformRole(String role) {
        return platformRoles.contains(role);
    }
}
