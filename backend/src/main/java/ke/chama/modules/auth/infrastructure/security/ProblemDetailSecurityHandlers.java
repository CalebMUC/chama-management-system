package ke.chama.modules.auth.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/**
 * 401/403 raised by the security filter chain happen before any controller, so
 * GlobalExceptionHandler never sees them. This writes the same ProblemDetail shape, on top of
 * the standard {@code WWW-Authenticate: Bearer ...} header Spring adds.
 */
class ProblemDetailSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final BearerTokenAuthenticationEntryPoint bearerEntryPoint = new BearerTokenAuthenticationEntryPoint();
    private final BearerTokenAccessDeniedHandler bearerAccessDenied = new BearerTokenAccessDeniedHandler();

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException {
        bearerEntryPoint.commence(request, response, exception);
        if (exception instanceof InvalidBearerTokenException) {
            write(response, HttpStatus.UNAUTHORIZED, "AUTH_INVALID_ACCESS_TOKEN",
                    "The access token is invalid or has expired");
        } else {
            write(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication is required");
        }
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException exception) throws IOException {
        bearerAccessDenied.handle(request, response, exception);
        write(response, HttpStatus.FORBIDDEN, "ACCESS_DENIED", "You do not have permission to perform this action");
    }

    /** All values are constants, so a hand-built JSON string is safe here. */
    private static void write(HttpServletResponse response, HttpStatus status, String code, String detail)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("""
                {"type":"about:blank","title":"%s","status":%d,"detail":"%s","code":"%s"}"""
                .formatted(status.getReasonPhrase(), status.value(), detail, code));
    }
}
