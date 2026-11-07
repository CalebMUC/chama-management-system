package ke.chama.shared.web;

import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.WebRequest;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The body of Spring Boot's {@code /error} page, which handles failures that never reach a
 * controller (for example an exception thrown in a servlet filter). It uses the same shape as
 * {@link ApiResponse} so clients see one error format everywhere. No exception message or
 * stack trace is included.
 */
@Component
public class ApiErrorAttributes extends DefaultErrorAttributes {

    @Override
    public Map<String, Object> getErrorAttributes(WebRequest webRequest, ErrorAttributeOptions options) {
        Map<String, Object> defaults = super.getErrorAttributes(webRequest, ErrorAttributeOptions.defaults());

        HttpStatus status = defaults.get("status") instanceof Integer code && HttpStatus.resolve(code) != null
                ? HttpStatus.resolve(code)
                : HttpStatus.INTERNAL_SERVER_ERROR;

        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", ApiError.defaultCode(status));
        error.put("status", status.value());
        error.put("path", defaults.get("path"));
        error.put("fields", List.of());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", ApiError.defaultMessage(status));
        body.put("data", null);
        body.put("error", error);
        body.put("timestamp", Instant.now());
        return body;
    }
}
