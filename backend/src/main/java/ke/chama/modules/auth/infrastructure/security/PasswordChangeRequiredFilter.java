package ke.chama.modules.auth.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * An account whose password a chama official set (see {@code UserProvisioning.createMemberAccount})
 * may only read its profile, change the password and sign out until the password is changed.
 * Everything else answers 403 {@code AUTH_PASSWORD_CHANGE_REQUIRED}.
 * <p>
 * Reads the {@code pwd_change_required} claim, so it costs no database lookup. Changing the
 * password issues a fresh token pair without the claim and ends every other session, so no token
 * that still carries it outlives the change by more than the access-token lifetime.
 */
class PasswordChangeRequiredFilter extends OncePerRequestFilter {

    static final String CODE = "AUTH_PASSWORD_CHANGE_REQUIRED";

    private static final Set<String> ALLOWED = Set.of(
            "GET /api/v1/auth/me",
            "POST /api/v1/auth/password/change",
            "POST /api/v1/auth/logout",
            "POST /api/v1/auth/logout-all",
            "POST /api/v1/auth/refresh");

    private final ApiErrorSecurityHandlers handlers;

    PasswordChangeRequiredFilter(ApiErrorSecurityHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (mustChangePassword() && !ALLOWED.contains(request.getMethod() + " " + path(request))) {
            handlers.write(request, response, HttpStatus.FORBIDDEN, CODE,
                    "Choose a new password to replace the temporary one before you continue.");
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean mustChangePassword() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication instanceof JwtAuthenticationToken jwt
                && Boolean.TRUE.equals(jwt.getToken().getClaimAsBoolean(JwtAccessTokenIssuer.PASSWORD_CHANGE_REQUIRED_CLAIM));
    }

    private static String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        String path = context != null && !context.isEmpty() && uri.startsWith(context) ? uri.substring(context.length()) : uri;
        return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }
}
