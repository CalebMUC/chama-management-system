package ke.chama.modules.auth.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import ke.chama.shared.web.ApiError;
import ke.chama.shared.web.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 401/403 raised by the security filter chain happen before any controller, so
 * GlobalExceptionHandler never sees them. This writes the same {@link ApiResponse} error shape,
 * on top of the standard {@code WWW-Authenticate: Bearer ...} header Spring adds.
 */
class ApiErrorSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final BearerTokenAuthenticationEntryPoint bearerEntryPoint = new BearerTokenAuthenticationEntryPoint();
    private final BearerTokenAccessDeniedHandler bearerAccessDenied = new BearerTokenAccessDeniedHandler();
    private final JsonMapper jsonMapper;

    ApiErrorSecurityHandlers(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException {
        bearerEntryPoint.commence(request, response, exception);
        if (exception instanceof InvalidBearerTokenException) {
            write(request, response, HttpStatus.UNAUTHORIZED, "AUTH_INVALID_ACCESS_TOKEN",
                    "Your session has expired or the access token is invalid. Log in again or refresh the token.");
        } else {
            write(request, response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                    ApiError.defaultMessage(HttpStatus.UNAUTHORIZED));
        }
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException exception) throws IOException {
        bearerAccessDenied.handle(request, response, exception);
        write(request, response, HttpStatus.FORBIDDEN, "ACCESS_DENIED", ApiError.defaultMessage(HttpStatus.FORBIDDEN));
    }

    private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status,
                       String code, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ApiResponse<Void> body = ApiResponse.failure(message, ApiError.of(code, status, request.getRequestURI()));
        jsonMapper.writeValue(response.getOutputStream(), body);
    }
}
