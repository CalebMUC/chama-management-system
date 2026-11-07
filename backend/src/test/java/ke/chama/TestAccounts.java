package ke.chama;

import com.jayway.jsonpath.JsonPath;
import ke.chama.shared.tenancy.TenantContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.Locale;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Creates real chamas and accounts through the API for integration tests: onboard a chama,
 * register members with its code, verify their email from the recorded message, log in.
 */
public final class TestAccounts {

    public static final String PASSWORD = "Sacco2026pass";
    public static final String PLATFORM_ADMIN_EMAIL = "platform-admin@chama.test";

    private final MockMvc mvc;
    private final RecordingEmailSender emails;
    private Account platformAdmin;

    public TestAccounts(MockMvc mvc, RecordingEmailSender emails) {
        this.mvc = mvc;
        this.emails = emails;
    }

    /** A chama (tenant) created for one test, with its first chairperson already logged in. */
    public record TestChama(UUID id, String code, Account chair) {
    }

    public record Account(String email, UUID userId, String accessToken, String chamaCode) {

        public MockHttpServletRequestBuilder get(String url, Object... vars) {
            return authorized(MockMvcRequestBuilders.get(url, vars));
        }

        public MockHttpServletRequestBuilder post(String url, Object... vars) {
            return authorized(MockMvcRequestBuilders.post(url, vars));
        }

        public MockHttpServletRequestBuilder post(String url, String json, Object... vars) {
            return post(url, vars).contentType(MediaType.APPLICATION_JSON).content(json);
        }

        public MockHttpServletRequestBuilder put(String url, String json, Object... vars) {
            return authorized(MockMvcRequestBuilders.put(url, vars))
                    .contentType(MediaType.APPLICATION_JSON).content(json);
        }

        private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder builder) {
            return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        }
    }

    // ---- Chamas -------------------------------------------------------------------------------

    public static String uniqueCode(String prefix) {
        return (prefix.toLowerCase(Locale.ROOT) + "-" + UUID.randomUUID().toString().substring(0, 8));
    }

    /** Onboards a new chama; its administrator verifies their email and logs in. */
    public TestChama onboard(String chairFirstName) throws Exception {
        String code = uniqueCode("chama");
        String email = uniqueEmail(chairFirstName);
        String body = mvc.perform(platformAdmin().post("/api/v1/platform/chamas", """
                        {"chama":{"name":"%s Chama","code":"%s",
                         "admin":{"email":"%s","phoneNumber":"0712345678","password":"%s",
                                  "firstName":"%s","lastName":"Test"}}}"""
                        .formatted(chairFirstName, code, email, PASSWORD, chairFirstName)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID chamaId = UUID.fromString(JsonPath.read(body, "$.data.chama.id"));
        verifyEmail(code, email);
        return new TestChama(chamaId, code, login(code, email));
    }

    /** Onboards a chama without logging anyone in; returns its code. For auth tests that only need a tenant. */
    public String newChama() throws Exception {
        String code = uniqueCode("chama");
        mvc.perform(platformAdmin().post("/api/v1/platform/chamas", """
                        {"chama":{"name":"Test Chama","code":"%s",
                         "admin":{"email":"%s","phoneNumber":"0712345678","password":"%s",
                                  "firstName":"Admin","lastName":"Test"}}}"""
                        .formatted(code, uniqueEmail("admin"), PASSWORD)))
                .andExpect(status().isCreated());
        return code;
    }

    // ---- Members ------------------------------------------------------------------------------

    /** Registers in the chama, verifies the email and logs in. The membership is still pending approval. */
    public Account applicant(TestChama chama, String firstName) throws Exception {
        String email = register(chama.code(), firstName);
        verifyEmail(chama.code(), email);
        return login(chama.code(), email);
    }

    public Account unverified(TestChama chama, String firstName) throws Exception {
        return login(chama.code(), register(chama.code(), firstName));
    }

    /** An applicant approved by the chairperson: an ACTIVE member with the MEMBER role. */
    public Account member(TestChama chama, String firstName) throws Exception {
        Account applicant = applicant(chama, firstName);
        mvc.perform(chama.chair().post("/api/v1/members/{id}/approve", membershipId(applicant)))
                .andExpect(status().isOk());
        return applicant;
    }

    /** A UUID (not a String), so {@code post(url, id)} fills the path variable instead of being sent as the body. */
    public UUID membershipId(Account account) throws Exception {
        String body = mvc.perform(account.get("/api/v1/members/me")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.data.membership.membershipId"));
    }

    // ---- Accounts -----------------------------------------------------------------------------

    public Account login(String chamaCode, String email) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD));
        if (chamaCode != null) {
            request.header(TenantContext.HEADER, chamaCode);
        }
        String body = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String accessToken = JsonPath.read(body, "$.data.accessToken");
        String me = mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andReturn().getResponse().getContentAsString();
        return new Account(email, UUID.fromString(JsonPath.read(me, "$.data.id")), accessToken, chamaCode);
    }

    /** The platform administrator created at startup (see {@link IntegrationTest}); logged in once per helper. */
    public Account platformAdmin() throws Exception {
        if (platformAdmin == null) {
            platformAdmin = login(null, PLATFORM_ADMIN_EMAIL);
        }
        return platformAdmin;
    }

    public String register(String chamaCode, String firstName) throws Exception {
        String email = uniqueEmail(firstName);
        mvc.perform(post("/api/v1/auth/register").header(TenantContext.HEADER, chamaCode)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"email":"%s","phoneNumber":"0712345678","password":"%s","firstName":"%s","lastName":"Test"}"""
                                .formatted(email, PASSWORD, firstName)))
                .andExpect(status().isCreated());
        return email;
    }

    public void verifyEmail(String chamaCode, String email) throws Exception {
        String token = RecordingEmailSender.tokenFrom(emails.awaitEmail(email, s -> s.contains("Verify")));
        mvc.perform(post("/api/v1/auth/email/verify").header(TenantContext.HEADER, chamaCode)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"%s\"}".formatted(token)))
                .andExpect(status().isOk());
    }

    public static String uniqueEmail(String firstName) {
        return firstName.toLowerCase(Locale.ROOT) + "-" + UUID.randomUUID() + "@example.com";
    }
}
