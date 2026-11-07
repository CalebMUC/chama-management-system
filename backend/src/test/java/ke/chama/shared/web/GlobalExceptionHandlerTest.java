package ke.chama.shared.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import ke.chama.shared.domain.exception.DomainException;
import ke.chama.shared.domain.exception.ErrorType;
import ke.chama.shared.domain.exception.RetryAfterAware;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Every failure comes back in the ApiResponse error envelope, with a user-safe message. */
class GlobalExceptionHandlerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @ParameterizedTest
    @CsvSource({
            "VALIDATION, 400",
            "UNAUTHORIZED, 401",
            "FORBIDDEN, 403",
            "NOT_FOUND, 404",
            "CONFLICT, 409",
            "BUSINESS_RULE, 422"
    })
    void mapsDomainErrorTypesToHttpStatus(ErrorType type, int expectedStatus) throws Exception {
        mvc.perform(get("/domain/{type}", type))
                .andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("failed with " + type))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("TEST_" + type))
                .andExpect(jsonPath("$.error.status").value(expectedStatus))
                .andExpect(jsonPath("$.error.path").value("/domain/" + type))
                .andExpect(jsonPath("$.error.fields").value(empty()))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void tooManyRequestsTellsTheClientWhenToRetry() throws Exception {
        mvc.perform(get("/limited"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "90"))
                .andExpect(jsonPath("$.error.code").value("TEST_LOCKED"))
                .andExpect(jsonPath("$.error.retryAfterSeconds").value(90));
    }

    @Test
    void accessDeniedIsForbiddenNotInternalError() throws Exception {
        mvc.perform(get("/denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
    }

    @Test
    void concurrentEditIsConflict() throws Exception {
        mvc.perform(get("/stale"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONCURRENT_MODIFICATION"));
    }

    @Test
    void uniqueConstraintRaceIsConflictWithoutSqlDetails() throws Exception {
        mvc.perform(get("/duplicate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DATA_CONFLICT"))
                .andExpect(jsonPath("$.message").value("The request conflicts with existing data"));
    }

    @Test
    void unexpectedErrorsDoNotLeakDetails() throws Exception {
        mvc.perform(get("/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
    }

    @Test
    void validationErrorsListEachField() throws Exception {
        mvc.perform(post("/validate").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\",\"amount\":-5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("One or more fields are invalid"))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fields.length()").value(2))
                .andExpect(jsonPath("$.error.fields[?(@.field=='name')].message").value("must not be blank"))
                .andExpect(jsonPath("$.error.fields[?(@.field=='amount')].message").value("must be greater than 0"));
    }

    @Test
    void malformedJsonIsReportedWithoutParserInternals() throws Exception {
        mvc.perform(post("/validate").contentType(MediaType.APPLICATION_JSON).content("{\"name\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.message").value("The request body is missing or is not valid JSON"));
    }

    @Test
    void unknownEnumValueNamesTheFieldAndTheAllowedValues() throws Exception {
        mvc.perform(put("/types").contentType(MediaType.APPLICATION_JSON).content("{\"types\":[\"CONFLICT\",\"NOPE\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fields[0].field").value("types[1]"))
                .andExpect(jsonPath("$.error.fields[0].message").value(containsString("must be one of: VALIDATION")))
                .andExpect(jsonPath("$.error.fields[0].message").value(org.hamcrest.Matchers.not(containsString("ke.chama"))));
    }

    @Test
    void badUuidInThePathIsAnInvalidParameter() throws Exception {
        mvc.perform(get("/items/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.error.fields[0].field").value("id"))
                .andExpect(jsonPath("$.error.fields[0].message").value("must be a valid UUID"));
    }

    @Test
    void missingQueryParameterIsNamed() throws Exception {
        mvc.perform(get("/search"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MISSING_PARAMETER"))
                .andExpect(jsonPath("$.error.fields[0].field").value("q"));
    }

    @Test
    void wrongHttpMethodIsMethodNotAllowed() throws Exception {
        mvc.perform(post("/stale"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").value("Method POST is not supported here. Use GET"));
    }

    @Test
    void unknownUrlIsNotFound() throws Exception {
        mvc.perform(get("/no/such/endpoint"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("The requested resource was not found"));
    }

    @RestController
    static class ThrowingController {

        @GetMapping("/domain/{type}")
        void domain(@PathVariable ErrorType type) {
            throw new TestDomainException(type);
        }

        @GetMapping("/limited")
        void limited() {
            throw new LockedException();
        }

        @GetMapping("/denied")
        void denied() {
            throw new AccessDeniedException("nope");
        }

        @GetMapping("/stale")
        void stale() {
            throw new OptimisticLockingFailureException("stale");
        }

        @GetMapping("/duplicate")
        void duplicate() {
            throw new DataIntegrityViolationException("duplicate key value violates unique constraint \"users_email_key\"");
        }

        @GetMapping("/boom")
        void boom() {
            throw new IllegalStateException("SELECT * FROM secret_table failed");
        }

        @PostMapping("/validate")
        void validate(@Valid @RequestBody Named body) {
        }

        @org.springframework.web.bind.annotation.PutMapping("/types")
        void types(@RequestBody Types body) {
        }

        @GetMapping("/items/{id}")
        void item(@PathVariable UUID id) {
        }

        @GetMapping("/search")
        void search(@RequestParam String q) {
        }
    }

    record Named(@NotBlank String name, @Positive Integer amount) {
    }

    record Types(List<ErrorType> types) {
    }

    static class TestDomainException extends DomainException {
        TestDomainException(ErrorType type) {
            super("TEST_" + type, type, "failed with " + type);
        }
    }

    static class LockedException extends DomainException implements RetryAfterAware {
        LockedException() {
            super("TEST_LOCKED", ErrorType.TOO_MANY_REQUESTS, "Too many attempts");
        }

        @Override
        public Duration retryAfter() {
            return Duration.ofSeconds(90);
        }
    }
}
