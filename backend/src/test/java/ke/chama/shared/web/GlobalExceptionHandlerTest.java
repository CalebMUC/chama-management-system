package ke.chama.shared.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import ke.chama.shared.domain.exception.DomainException;
import ke.chama.shared.domain.exception.ErrorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andExpect(jsonPath("$.code").value("TEST_" + type))
                .andExpect(jsonPath("$.detail").value("failed with " + type));
    }

    @Test
    void accessDeniedIsForbiddenNotInternalError() throws Exception {
        mvc.perform(get("/denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void concurrentEditIsConflict() throws Exception {
        mvc.perform(get("/stale"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    }

    @Test
    void unexpectedErrorsDoNotLeakDetails() throws Exception {
        mvc.perform(get("/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"));
    }

    @Test
    void validationErrorsListEachField() throws Exception {
        mvc.perform(post("/validate").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @RestController
    static class ThrowingController {

        @GetMapping("/domain/{type}")
        void domain(@PathVariable ErrorType type) {
            throw new TestDomainException(type);
        }

        @GetMapping("/denied")
        void denied() {
            throw new AccessDeniedException("nope");
        }

        @GetMapping("/stale")
        void stale() {
            throw new OptimisticLockingFailureException("stale");
        }

        @GetMapping("/boom")
        void boom() {
            throw new IllegalStateException("SELECT * FROM secret_table failed");
        }

        @PostMapping("/validate")
        void validate(@Valid @RequestBody Named body) {
        }
    }

    record Named(@NotBlank String name) {
    }

    static class TestDomainException extends DomainException {
        TestDomainException(ErrorType type) {
            super("TEST_" + type, type, "failed with " + type);
        }
    }
}
