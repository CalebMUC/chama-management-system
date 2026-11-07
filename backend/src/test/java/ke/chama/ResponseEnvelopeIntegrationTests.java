package ke.chama;

import ke.chama.TestAccounts.Account;
import ke.chama.shared.tenancy.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The response contract a frontend relies on, through the full stack (security filters,
 * controllers, exception handling): one envelope for successes and errors.
 */
@IntegrationTest
class ResponseEnvelopeIntegrationTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    RecordingEmailSender emails;

    TestAccounts accounts;

    @BeforeEach
    void setUp() {
        accounts = new TestAccounts(mvc, emails);
    }

    @Test
    void successWrapsTheResultInData() throws Exception {
        String email = "envelope-" + UUID.randomUUID() + "@example.com";
        mvc.perform(post("/api/v1/auth/register").header(TenantContext.HEADER, accounts.newChama())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"email":"%s","phoneNumber":"0712345678","password":"Sacco2026pass",
                         "firstName":"Wanjiru","lastName":"Kariuki"}""".formatted(email)))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.data.email").value(email))
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.meta").doesNotExist())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void operationsWithNothingToReturnStillAnswerWithAMessage() throws Exception {
        Account account = accounts.onboard("Mwangi").chair();

        mvc.perform(account.post("/api/v1/auth/logout-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Logged out of all devices"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void pagedListsPutItemsInDataAndPagingInMeta() throws Exception {
        Account chair = accounts.onboard("Njeri").chair();

        mvc.perform(chair.get("/api/v1/members?size=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].memberNumber").value(1))
                .andExpect(jsonPath("$.meta.page").value(0))
                .andExpect(jsonPath("$.meta.size").value(10))
                .andExpect(jsonPath("$.meta.totalElements").value(1))
                .andExpect(jsonPath("$.meta.totalPages").value(1))
                .andExpect(jsonPath("$.meta.hasNext").value(false));
    }

    @Test
    void missingTokenIsAnErrorEnvelopeFromTheSecurityFilter() throws Exception {
        mvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Authentication is required"))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.error.status").value(401))
                .andExpect(jsonPath("$.error.path").value("/api/v1/auth/me"))
                .andExpect(jsonPath("$.error.fields").value(empty()));
    }

    @Test
    void expiredOrForgedTokenSaysSo() throws Exception {
        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_ACCESS_TOKEN"));
    }

    @Test
    void validationErrorsPointAtTheFormFields() throws Exception {
        mvc.perform(post("/api/v1/auth/register").header(TenantContext.HEADER, accounts.newChama())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.path").value("/api/v1/auth/register"))
                .andExpect(jsonPath("$.error.fields[?(@.field=='email')]").isNotEmpty())
                .andExpect(jsonPath("$.error.fields[?(@.field=='password')]").isNotEmpty())
                .andExpect(jsonPath("$.error.fields[?(@.field=='firstName')]").isNotEmpty());
    }

    @Test
    void unknownEndpointIsNotFoundInTheSameShape() throws Exception {
        Account account = accounts.onboard("Kiprop").chair();

        mvc.perform(account.get("/api/v1/no-such-endpoint"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error.path").value("/api/v1/no-such-endpoint"));
    }

    @Test
    void invalidIdInTheUrlIsABadRequestNotAServerError() throws Exception {
        Account account = accounts.onboard("Auma").chair();

        mvc.perform(account.post("/api/v1/members/{id}/approve", (Object) "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.error.fields[0].field").value("membershipId"));
    }
}
