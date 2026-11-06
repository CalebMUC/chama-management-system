package ke.chama.shared.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.time.Instant;
import java.util.List;
import java.util.function.Function;

/**
 * The one JSON shape every endpoint returns, for successes and errors alike, so a client
 * handles all responses the same way:
 * <pre>{@code
 * if (body.success) use(body.data)          // body.meta holds paging for lists
 * else              show(body.message)      // body.error.code to branch, body.error.fields for forms
 * }</pre>
 * Success:
 * <pre>{@code
 * { "success": true, "message": "Chama created", "data": { ... }, "timestamp": "2026-11-06T03:10:00Z" }
 * }</pre>
 * Error:
 * <pre>{@code
 * { "success": false, "message": "One or more fields are invalid", "data": null,
 *   "error": { "code": "VALIDATION_FAILED", "status": 400, "path": "/api/v1/auth/register",
 *              "fields": [ { "field": "email", "message": "must be a well-formed email address" } ] },
 *   "timestamp": "2026-11-06T03:10:00Z" }
 * }</pre>
 * {@code success}, {@code message}, {@code data} and {@code timestamp} are always present;
 * {@code meta} only on paged lists, {@code error} only on failures.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"success", "message", "data", "meta", "error", "timestamp"})
public record ApiResponse<T>(
        @Schema(description = "true for 2xx responses, false otherwise")
        boolean success,
        @Schema(description = "Human-readable outcome, safe to show to the user", example = "Chama created")
        String message,
        @JsonInclude(JsonInclude.Include.ALWAYS)
        @Schema(description = "The result. null when the operation returns nothing and on errors")
        T data,
        @Schema(description = "Paging details, present on paged lists only")
        PageMeta meta,
        @Schema(description = "Error details, present on failures only")
        ApiError error,
        Instant timestamp) {

    public static final String DEFAULT_SUCCESS_MESSAGE = "OK";

    public static <T> ApiResponse<T> ok(T data) {
        return ok(data, DEFAULT_SUCCESS_MESSAGE);
    }

    public static <T> ApiResponse<T> ok(T data, String message) {
        return new ApiResponse<>(true, message, data, null, null, Instant.now());
    }

    /** For operations with nothing to return, e.g. logout. */
    public static ApiResponse<Void> message(String message) {
        return new ApiResponse<>(true, message, null, null, null, Instant.now());
    }

    /** The page's items as {@code data}, with paging in {@code meta}. */
    public static <T> ApiResponse<List<T>> page(Page<T> page) {
        return new ApiResponse<>(true, DEFAULT_SUCCESS_MESSAGE, page.getContent(), PageMeta.from(page), null,
                Instant.now());
    }

    public static <S, T> ApiResponse<List<T>> page(Page<S> page, Function<? super S, ? extends T> mapper) {
        return page(page.map(mapper));
    }

    public static ApiResponse<Void> failure(String message, ApiError error) {
        return new ApiResponse<>(false, message, null, null, error, Instant.now());
    }
}
