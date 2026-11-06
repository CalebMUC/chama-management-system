package ke.chama;

import com.jayway.jsonpath.JsonPath;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Creates real accounts through the API (register, verify by email, log in) for integration tests. */
public final class TestAccounts {

    public static final String PASSWORD = "Sacco2026pass";

    private final MockMvc mvc;
    private final RecordingEmailSender emails;

    public TestAccounts(MockMvc mvc, RecordingEmailSender emails) {
        this.mvc = mvc;
        this.emails = emails;
    }

    public record Account(String email, UUID userId, String accessToken) {

        public MockHttpServletRequestBuilder get(String url, Object... vars) {
            return authorized(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url, vars));
        }

        public MockHttpServletRequestBuilder post(String url, Object... vars) {
            return authorized(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url, vars));
        }

        public MockHttpServletRequestBuilder post(String url, String json, Object... vars) {
            return post(url, vars).contentType(MediaType.APPLICATION_JSON).content(json);
        }

        public MockHttpServletRequestBuilder put(String url, String json, Object... vars) {
            return authorized(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(url, vars))
                    .contentType(MediaType.APPLICATION_JSON).content(json);
        }

        private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder builder) {
            return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        }
    }

    public Account verified(String firstName) throws Exception {
        String email = register(firstName);
        String token = RecordingEmailSender.tokenFrom(emails.awaitEmail(email, s -> s.contains("Verify")));
        mvc.perform(post("/api/v1/auth/email/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"%s\"}".formatted(token))).andExpect(status().isNoContent());
        return login(email);
    }

    public Account unverified(String firstName) throws Exception {
        return login(register(firstName));
    }

    public Account login(String email) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String accessToken = JsonPath.read(body, "$.accessToken");
        String me = mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andReturn().getResponse().getContentAsString();
        return new Account(email, UUID.fromString(JsonPath.read(me, "$.id")), accessToken);
    }

    private String register(String firstName) throws Exception {
        String email = firstName.toLowerCase() + "-" + UUID.randomUUID() + "@example.com";
        ResultActions result = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"%s","phoneNumber":"0712345678","password":"%s","firstName":"%s","lastName":"Test"}"""
                .formatted(email, PASSWORD, firstName)));
        result.andExpect(status().isCreated());
        return email;
    }
}
