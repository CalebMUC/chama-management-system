package ke.chama.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import ke.chama.shared.domain.exception.DomainException;
import ke.chama.shared.domain.exception.ErrorType;
import ke.chama.shared.domain.exception.RetryAfterAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindingResult;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.MismatchedInputException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Turns every error into the {@link ApiResponse} error shape:
 * <pre>{@code
 * { "success": false, "message": "Loan amount exceeds 3x savings", "data": null,
 *   "error": { "code": "LOAN_NOT_ELIGIBLE", "status": 422, "path": "/api/v1/...", "fields": [] },
 *   "timestamp": "..." }
 * }</pre>
 * {@code message} is always safe to show to the user. Unexpected errors are logged and returned
 * without internal details, so stack traces and SQL never reach the client.
 * <p>
 * Errors raised before a controller is reached are written in the same shape by the security
 * handlers (401/403) and {@link ApiErrorAttributes} (anything else).
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    static final String VALIDATION_MESSAGE = "One or more fields are invalid";

    // ---- Application errors -------------------------------------------------------------------

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiResponse<Void>> handleDomain(DomainException ex, HttpServletRequest request) {
        HttpStatus status = toStatus(ex.getType());
        log.debug("Domain rejection {}: {}", ex.getCode(), ex.getMessage());

        if (ex instanceof RetryAfterAware retryable) {
            long seconds = Math.max(1, Math.ceilDiv(retryable.retryAfter().toMillis(), 1000));
            ApiError error = new ApiError(ex.getCode(), status.value(), request.getRequestURI(), List.of(), seconds);
            return ResponseEntity.status(status)
                    .header(HttpHeaders.RETRY_AFTER, Long.toString(seconds))
                    .body(ApiResponse.failure(ex.getMessage(), error));
        }
        return failure(status, ex.getCode(), ex.getMessage(), request.getRequestURI(), List.of());
    }

    /**
     * Thrown by {@code @PreAuthorize} inside controller methods. It must be handled explicitly,
     * otherwise the catch-all handler below would turn a 403 into a 500.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return failure(HttpStatus.FORBIDDEN, "ACCESS_DENIED", ApiError.defaultMessage(HttpStatus.FORBIDDEN),
                request.getRequestURI(), List.of());
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthentication(AuthenticationException ex,
                                                                  HttpServletRequest request) {
        return failure(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", ApiError.defaultMessage(HttpStatus.UNAUTHORIZED),
                request.getRequestURI(), List.of());
    }

    /** Two users changed the same record at once (the @Version check failed). */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLock(OptimisticLockingFailureException ex,
                                                                  HttpServletRequest request) {
        return failure(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                "This record was changed by someone else. Reload it and try again.",
                request.getRequestURI(), List.of());
    }

    /**
     * A database constraint rejected the write, typically two requests racing past an
     * "already exists?" check (e.g. registering the same email twice at once).
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrity(DataIntegrityViolationException ex,
                                                                 HttpServletRequest request) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return failure(HttpStatus.CONFLICT, "DATA_CONFLICT", "The request conflicts with existing data",
                request.getRequestURI(), List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception", ex);
        return failure(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                ApiError.defaultMessage(HttpStatus.INTERNAL_SERVER_ERROR), request.getRequestURI(), List.of());
    }

    // ---- Spring MVC errors: bad input, unknown URLs, wrong methods ---------------------------

    /** Bean Validation failures on {@code @Valid} request bodies, one entry per field. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        return respond(ex, headers, HttpStatus.BAD_REQUEST, VALIDATION_FAILED, VALIDATION_MESSAGE,
                fieldErrors(ex.getBindingResult()), request);
    }

    /** Constraint failures on {@code @RequestParam}/{@code @PathVariable} arguments. */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers,
                                                                            HttpStatusCode status,
                                                                            WebRequest request) {
        List<ApiError.FieldError> fields = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> result.getResolvableErrors().forEach(error ->
                fields.add(new ApiError.FieldError(result.getMethodParameter().getParameterName(),
                        orDefault(error.getDefaultMessage())))));
        return respond(ex, headers, HttpStatus.BAD_REQUEST, VALIDATION_FAILED, VALIDATION_MESSAGE, fields, request);
    }

    /** Body missing, not JSON, or a value of the wrong type (e.g. an unknown enum constant). */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        if (ex.getMostSpecificCause() instanceof JacksonException jackson && !jackson.getPath().isEmpty()) {
            String field = jsonPath(jackson);
            String problem = jackson instanceof MismatchedInputException mismatch
                    ? expected(mismatch.getTargetType())
                    : "has an invalid value";
            return respond(ex, headers, HttpStatus.BAD_REQUEST, VALIDATION_FAILED, VALIDATION_MESSAGE,
                    List.of(new ApiError.FieldError(field, problem)), request);
        }
        return respond(ex, headers, HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "The request body is missing or is not valid JSON", List.of(), request);
    }

    /** A query or path parameter that cannot be converted, e.g. {@code /chamas/not-a-uuid}. */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
                                                        HttpStatusCode status, WebRequest request) {
        String name = ex.getPropertyName() == null ? "parameter" : ex.getPropertyName();
        return respond(ex, headers, HttpStatus.BAD_REQUEST, "INVALID_PARAMETER",
                "Invalid value for '" + name + "'",
                List.of(new ApiError.FieldError(name, expected(ex.getRequiredType()))), request);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(MissingServletRequestParameterException ex,
                                                                          HttpHeaders headers,
                                                                          HttpStatusCode status,
                                                                          WebRequest request) {
        return respond(ex, headers, HttpStatus.BAD_REQUEST, "MISSING_PARAMETER",
                "Required parameter '" + ex.getParameterName() + "' is missing",
                List.of(new ApiError.FieldError(ex.getParameterName(), "is required")), request);
    }

    /**
     * Every other Spring MVC error (404 unknown URL, 405, 415, ...) arrives here with a
     * ProblemDetail body; it is replaced with the ApiResponse shape. Bodies built by the
     * handlers above are already ApiResponses and pass straight through.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ApiResponse<?>) {
            return super.handleExceptionInternal(ex, body, headers, statusCode, request);
        }
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        if (status.is5xxServerError()) {
            log.error("Request failed with {}", status.value(), ex);
        }
        String message = switch (ex) {
            case HttpRequestMethodNotSupportedException e ->
                    "Method " + e.getMethod() + " is not supported here. Use " + String.join(", ",
                            e.getSupportedMethods() == null ? new String[0] : e.getSupportedMethods());
            case HttpMediaTypeNotSupportedException e -> ApiError.defaultMessage(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
            case MissingPathVariableException e -> ApiError.defaultMessage(HttpStatus.INTERNAL_SERVER_ERROR);
            default -> ApiError.defaultMessage(status);
        };
        ApiResponse<Void> converted = ApiResponse.failure(message,
                ApiError.of(ApiError.defaultCode(status), status, path(request)));
        return super.handleExceptionInternal(ex, converted, headers, status, request);
    }

    // ---- Helpers ------------------------------------------------------------------------------

    static HttpStatus toStatus(ErrorType type) {
        return switch (type) {
            case VALIDATION -> HttpStatus.BAD_REQUEST;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case BUSINESS_RULE -> HttpStatus.UNPROCESSABLE_ENTITY;
            case TOO_MANY_REQUESTS -> HttpStatus.TOO_MANY_REQUESTS;
        };
    }

    private static ResponseEntity<ApiResponse<Void>> failure(HttpStatus status, String code, String message,
                                                             String path, List<ApiError.FieldError> fields) {
        return ResponseEntity.status(status)
                .body(ApiResponse.failure(message, new ApiError(code, status.value(), path, fields, null)));
    }

    private ResponseEntity<Object> respond(Exception ex, HttpHeaders headers, HttpStatus status, String code,
                                           String message, List<ApiError.FieldError> fields, WebRequest request) {
        ApiResponse<Void> body = ApiResponse.failure(message,
                new ApiError(code, status.value(), path(request), fields, null));
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    private static List<ApiError.FieldError> fieldErrors(BindingResult result) {
        List<ApiError.FieldError> fields = new ArrayList<>();
        result.getFieldErrors().forEach(fe ->
                fields.add(new ApiError.FieldError(fe.getField(), orDefault(fe.getDefaultMessage()))));
        // Class-level constraints (e.g. "end date after start date") have no single field.
        result.getGlobalErrors().forEach(ge ->
                fields.add(new ApiError.FieldError(ge.getObjectName(), orDefault(ge.getDefaultMessage()))));
        return fields;
    }

    /** {@code settings.loanMaxMultiplier}, {@code roles[0]}: the path as it appears in the client's JSON. */
    private static String jsonPath(JacksonException ex) {
        StringBuilder path = new StringBuilder();
        for (JacksonException.Reference ref : ex.getPath()) {
            if (ref.getPropertyName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(ref.getPropertyName());
            } else if (ref.getIndex() >= 0) {
                path.append('[').append(ref.getIndex()).append(']');
            }
        }
        return path.isEmpty() ? "body" : path.toString();
    }

    /** Describes the expected type without exposing Java class names. */
    private static String expected(Class<?> type) {
        if (type == null) {
            return "has an invalid value";
        }
        if (type.isEnum()) {
            return "must be one of: " + Arrays.stream(type.getEnumConstants())
                    .map(Object::toString).collect(Collectors.joining(", "));
        }
        if (type == UUID.class) {
            return "must be a valid UUID";
        }
        if (Number.class.isAssignableFrom(type) || (type.isPrimitive() && type != boolean.class)) {
            return "must be a number";
        }
        if (type == Boolean.class || type == boolean.class) {
            return "must be true or false";
        }
        return "has an invalid value";
    }

    private static String orDefault(String message) {
        return message == null ? "is invalid" : message;
    }

    private static String path(WebRequest request) {
        return request instanceof ServletWebRequest servlet ? servlet.getRequest().getRequestURI() : null;
    }
}
