package ke.chama.shared.web;

import ke.chama.shared.domain.exception.DomainException;
import ke.chama.shared.domain.exception.ErrorType;
import ke.chama.shared.domain.exception.RetryAfterAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;
import java.util.Map;

/**
 * Turns every error into an RFC 9457 {@code application/problem+json} body:
 * <pre>{@code
 * { "type": "about:blank", "title": "Unprocessable Entity", "status": 422,
 *   "detail": "Loan amount exceeds 3x savings", "code": "LOAN_NOT_ELIGIBLE" }
 * }</pre>
 * Clients branch on {@code code}. Unexpected errors are logged and returned without internal
 * details, so stack traces and SQL never reach the client.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    static final String CODE = "code";

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ProblemDetail> handleDomain(DomainException ex) {
        HttpStatus status = toStatus(ex.getType());
        if (status.is5xxServerError()) {
            log.error("Domain failure {}", ex.getCode(), ex);
        } else {
            log.debug("Domain rejection {}: {}", ex.getCode(), ex.getMessage());
        }
        ResponseEntity<ProblemDetail> response = problem(status, ex.getMessage(), ex.getCode());
        if (ex instanceof RetryAfterAware retryable) {
            long seconds = Math.max(1, Math.ceilDiv(retryable.retryAfter().toMillis(), 1000));
            return ResponseEntity.status(status)
                    .header(HttpHeaders.RETRY_AFTER, Long.toString(seconds))
                    .body(response.getBody());
        }
        return response;
    }

    /**
     * Thrown by {@code @PreAuthorize} inside controller methods. It must be handled explicitly,
     * otherwise the catch-all handler below would turn a 403 into a 500.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        return problem(HttpStatus.FORBIDDEN, "You do not have permission to perform this action", "ACCESS_DENIED");
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex) {
        return problem(HttpStatus.UNAUTHORIZED, "Authentication is required", "UNAUTHENTICATED");
    }

    /** Two users changed the same record at once (the @Version check failed). */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLock(OptimisticLockingFailureException ex) {
        return problem(HttpStatus.CONFLICT,
                "This record was changed by someone else. Reload it and try again.",
                "CONCURRENT_MODIFICATION");
    }

    /**
     * A database constraint rejected the write, typically two requests racing past an
     * "already exists?" check (e.g. registering the same email twice at once).
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return problem(HttpStatus.CONFLICT, "The request conflicts with existing data", "DATA_CONFLICT");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", "INTERNAL_ERROR");
    }

    /** Bean Validation failures on {@code @Valid} request bodies, with one entry per field. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of(
                        "field", fe.getField(),
                        "message", fe.getDefaultMessage() == null ? "is invalid" : fe.getDefaultMessage()))
                .toList();

        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        body.setTitle(HttpStatus.BAD_REQUEST.getReasonPhrase());
        body.setProperty(CODE, "VALIDATION_FAILED");
        body.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(body);
    }

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

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail, String code) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setTitle(status.getReasonPhrase());
        body.setProperty(CODE, code);
        return ResponseEntity.status(status).body(body);
    }
}
