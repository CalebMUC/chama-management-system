package ke.chama.shared.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * The {@code error} part of a failed {@link ApiResponse}.
 *
 * @param code              stable, machine-readable reason to branch on, e.g. {@code AUTH_ACCOUNT_LOCKED}.
 *                          Unlike {@code message}, it never changes wording.
 * @param status            the HTTP status, repeated for clients that only see the body
 * @param path              the request path that failed
 * @param fields            one entry per invalid input, for showing errors next to form fields.
 *                          Always present; empty when the error is not about specific fields.
 * @param retryAfterSeconds on 429, how long to wait before retrying (also sent as {@code Retry-After})
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        @Schema(example = "VALIDATION_FAILED") String code,
        @Schema(example = "400") int status,
        @Schema(example = "/api/v1/auth/register") String path,
        @JsonInclude(JsonInclude.Include.ALWAYS) List<FieldError> fields,
        Long retryAfterSeconds) {

    public ApiError {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }

    public static ApiError of(String code, HttpStatus status, String path) {
        return new ApiError(code, status.value(), path, List.of(), null);
    }

    /**
     * @param field   the input's name as the client sent it: a JSON property path such as
     *                {@code settings.loanMaxMultiplier} or {@code roles[0]}, or a query/path parameter
     * @param message what is wrong with it, e.g. "must not be blank"
     */
    public record FieldError(
            @Schema(example = "email") String field,
            @Schema(example = "must be a well-formed email address") String message) {
    }

    /** The code used when nothing more specific is known, e.g. for 404 on an unknown URL. */
    public static String defaultCode(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> "BAD_REQUEST";
            case UNAUTHORIZED -> "UNAUTHENTICATED";
            case FORBIDDEN -> "ACCESS_DENIED";
            case NOT_FOUND -> "NOT_FOUND";
            case METHOD_NOT_ALLOWED -> "METHOD_NOT_ALLOWED";
            case NOT_ACCEPTABLE -> "NOT_ACCEPTABLE";
            case CONFLICT -> "CONFLICT";
            case PAYLOAD_TOO_LARGE, CONTENT_TOO_LARGE -> "PAYLOAD_TOO_LARGE";
            case UNSUPPORTED_MEDIA_TYPE -> "UNSUPPORTED_MEDIA_TYPE";
            case UNPROCESSABLE_ENTITY, UNPROCESSABLE_CONTENT -> "BUSINESS_RULE_VIOLATION";
            case TOO_MANY_REQUESTS -> "TOO_MANY_REQUESTS";
            case SERVICE_UNAVAILABLE -> "SERVICE_UNAVAILABLE";
            default -> status.is5xxServerError() ? "INTERNAL_ERROR" : status.name();
        };
    }

    /** A user-facing message when nothing more specific is known. Never includes internals. */
    public static String defaultMessage(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> "The request is invalid";
            case UNAUTHORIZED -> "Authentication is required";
            case FORBIDDEN -> "You do not have permission to perform this action";
            case NOT_FOUND -> "The requested resource was not found";
            case METHOD_NOT_ALLOWED -> "This HTTP method is not supported for this endpoint";
            case NOT_ACCEPTABLE -> "The requested response format is not supported; use application/json";
            case UNSUPPORTED_MEDIA_TYPE -> "Unsupported content type; send application/json";
            case TOO_MANY_REQUESTS -> "Too many requests. Try again later.";
            case SERVICE_UNAVAILABLE -> "The service is temporarily unavailable. Try again shortly.";
            default -> status.is5xxServerError() ? "An unexpected error occurred" : status.getReasonPhrase();
        };
    }
}
