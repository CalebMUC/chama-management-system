package ke.chama.shared.security;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Access to the authenticated caller for the presentation/application layers of any module.
 * Use it to stamp accountable actions, e.g. {@code contribution.recordedBy(CurrentUser.id())}.
 * <p>
 * Never take the acting user's ID from a request body: a client could claim to be anyone.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    /** The authenticated caller. If there is none, throws an AuthenticationException (HTTP 401). */
    public static AuthenticatedUser require() {
        return find().orElseThrow(() -> new AuthenticationCredentialsNotFoundException("No authenticated user"));
    }

    public static UUID id() {
        return require().userId();
    }

    public static Optional<AuthenticatedUser> find() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token) {
            Jwt jwt = token.getToken();
            Set<String> roles = token.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .collect(Collectors.toUnmodifiableSet());
            return Optional.of(new AuthenticatedUser(UUID.fromString(jwt.getSubject()), jwt.getClaimAsString("email"), roles));
        }
        return Optional.empty();
    }
}
