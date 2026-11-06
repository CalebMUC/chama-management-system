package ke.chama.modules.auth;

import com.jayway.jsonpath.JsonPath;
import ke.chama.IntegrationTest;
import ke.chama.modules.auth.domain.event.UserRegisteredEvent;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End-to-end auth flows over HTTP against a real database. */
@IntegrationTest
@RecordApplicationEvents
class AuthFlowIntegrationTests {

    private static final String PASSWORD = "Sacco2026pass";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ApplicationEvents events;

    @Nested
    class Registration {

        @Test
        void createsUserWithNormalizedContactDetailsAndPublishesEvent() throws Exception {
            String email = uniqueEmail();

            register("  " + email.toUpperCase() + " ", "0712 345 678")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.email").value(email))
                    .andExpect(jsonPath("$.phoneNumber").value("+254712345678"))
                    .andExpect(jsonPath("$.roles").value(hasItem("ROLE_USER")))
                    .andExpect(jsonPath("$.createdAt").isString())
                    .andExpect(jsonPath("$.password").doesNotExist())
                    .andExpect(content().string(not(containsString(PASSWORD))));

            assertThat(events.stream(UserRegisteredEvent.class))
                    .anySatisfy(e -> {
                        assertThat(e.email()).isEqualTo(email);
                        assertThat(e.phoneNumber()).isEqualTo("+254712345678");
                        assertThat(e.eventId()).isNotNull();
                    });
        }

        @Test
        void storesOnlyAPasswordHash() throws Exception {
            String email = uniqueEmail();
            register(email, "0712345678").andExpect(status().isCreated());

            String stored = jdbc.queryForObject("select password from auth.users where email = ?", String.class, email);
            assertThat(stored).startsWith("{bcrypt}").doesNotContain(PASSWORD);
        }

        @Test
        void rejectsDuplicateEmailIgnoringCase() throws Exception {
            String email = uniqueEmail();
            register(email, "0712345678").andExpect(status().isCreated());

            register(email.toUpperCase(), "0722000000")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("AUTH_USER_ALREADY_EXISTS"));
        }

        @Test
        void validatesRequestFields() throws Exception {
            mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                            {"email":"not-an-email","phoneNumber":"0712345678","password":"short",
                             "firstName":"","lastName":"Wanjiku"}"""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[*].field").value(hasItem("email")))
                    .andExpect(jsonPath("$.errors[*].field").value(hasItem("password")))
                    .andExpect(jsonPath("$.errors[*].field").value(hasItem("firstName")));
        }

        @Test
        void rejectsInvalidPhoneNumber() throws Exception {
            register(uniqueEmail(), "12345")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_PHONE_NUMBER"));
        }
    }

    @Nested
    class Login {

        @Test
        void returnsTokenPairForValidCredentials() throws Exception {
            String email = registered();

            login(email, PASSWORD)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tokenType").value("Bearer"))
                    .andExpect(jsonPath("$.expiresIn").value(900))
                    .andExpect(jsonPath("$.accessToken").isString())
                    .andExpect(jsonPath("$.refreshToken").isString());
        }

        @Test
        void wrongPasswordAndUnknownEmailLookIdentical() throws Exception {
            String email = registered();

            String wrongPassword = login(email, "Wrong2026pass")
                    .andExpect(status().isUnauthorized())
                    .andReturn().getResponse().getContentAsString();
            String unknownEmail = login(uniqueEmail(), PASSWORD)
                    .andExpect(status().isUnauthorized())
                    .andReturn().getResponse().getContentAsString();

            assertThat(wrongPassword).isEqualTo(unknownEmail).contains("AUTH_INVALID_CREDENTIALS");
        }

        @Test
        void disabledAccountIsRefusedAfterPasswordCheck() throws Exception {
            String email = registered();
            jdbc.update("update auth.users set is_enabled = false where email = ?", email);

            login(email, PASSWORD)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("AUTH_ACCOUNT_DISABLED"));
        }
    }

    @Nested
    class LoginLockout {

        @Test
        void fiveWrongPasswordsLockEvenTheCorrectPassword() throws Exception {
            String email = registered();
            failLogins(email, 5);

            login(email, PASSWORD)
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                    .andExpect(jsonPath("$.code").value("AUTH_ACCOUNT_LOCKED"));
        }

        @Test
        void unknownEmailsLockTheSameWaySoAccountsCannotBeDiscovered() throws Exception {
            String ghost = uniqueEmail();
            failLogins(ghost, 5);

            login(ghost, PASSWORD)
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value("AUTH_ACCOUNT_LOCKED"));
        }

        @Test
        void aSuccessfulLoginResetsTheCount() throws Exception {
            String email = registered();
            failLogins(email, 4);
            login(email, PASSWORD).andExpect(status().isOk());
            failLogins(email, 4);

            login(email, PASSWORD).andExpect(status().isOk());
        }

        @Test
        void recordsEveryAttemptWithClientDetails() throws Exception {
            String email = registered();
            failLogins(email, 1);
            mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .header(HttpHeaders.USER_AGENT, "ChamaApp/1.0 (Android)")
                            .with(request -> {
                                request.setRemoteAddr("196.201.214.10");
                                return request;
                            })
                            .content("""
                                    {"email":"%s","password":"%s"}""".formatted(email, PASSWORD)))
                    .andExpect(status().isOk());

            List<java.util.Map<String, Object>> rows = jdbc.queryForList(
                    "select outcome, ip_address, user_agent, user_id from auth.login_attempts where email = ? order by attempted_at",
                    email);
            assertThat(rows).extracting(r -> r.get("outcome")).containsExactly("BAD_CREDENTIALS", "SUCCESS");
            assertThat(rows.getLast().get("ip_address")).isEqualTo("196.201.214.10");
            assertThat(rows.getLast().get("user_agent")).isEqualTo("ChamaApp/1.0 (Android)");
            assertThat(rows.getLast().get("user_id")).isNotNull();
        }

        private void failLogins(String email, int times) throws Exception {
            for (int i = 0; i < times; i++) {
                login(email, "Wrong2026pass").andExpect(status().isUnauthorized());
            }
        }
    }

    @Nested
    class AccessToken {

        @Test
        void unlocksProtectedEndpoints() throws Exception {
            String email = registered();
            Tokens tokens = tokensFrom(login(email, PASSWORD));

            mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.access()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").value(email));
        }

        @Test
        void missingTokenIsUnauthorizedWithProblemDetail() throws Exception {
            mvc.perform(get("/api/v1/auth/me"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("Bearer")))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }

        @Test
        void tamperedTokenIsRejected() throws Exception {
            Tokens tokens = tokensFrom(login(registered(), PASSWORD));
            String tampered = tokens.access().substring(0, tokens.access().length() - 4) + "AAAA";

            mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + tampered))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_INVALID_ACCESS_TOKEN"));
        }

        @Test
        void otherEndpointsRequireAuthentication() throws Exception {
            mvc.perform(get("/api/v1/chamas")).andExpect(status().isUnauthorized());
        }
    }

    @Nested
    class RefreshRotation {

        @Test
        void issuesNewPairAndStoresOnlyHashes() throws Exception {
            Tokens first = tokensFrom(login(registered(), PASSWORD));
            Tokens second = tokensFrom(refresh(first.refresh()).andExpect(status().isOk()));

            assertThat(second.refresh()).isNotEqualTo(first.refresh());
            assertThat(second.access()).isNotEqualTo(first.access());
            assertThat(jdbc.queryForObject("select count(*) from auth.refresh_tokens where token_hash = ?",
                    Integer.class, first.refresh())).isZero();
            assertThat(reasonFor(first.refresh())).isEqualTo("ROTATED");
        }

        @Test
        void reusingAnOldTokenRevokesEverySession() throws Exception {
            String email = registered();
            Tokens stolen = tokensFrom(login(email, PASSWORD));
            Tokens rotated = tokensFrom(refresh(stolen.refresh()));
            Tokens otherDevice = tokensFrom(login(email, PASSWORD));

            refresh(stolen.refresh())
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"));

            refresh(rotated.refresh()).andExpect(status().isUnauthorized());
            refresh(otherDevice.refresh()).andExpect(status().isUnauthorized());
            assertThat(reasonFor(otherDevice.refresh())).isEqualTo("REUSE_DETECTED");
        }

        @Test
        void unknownTokenIsRejected() throws Exception {
            refresh("not-a-real-token").andExpect(status().isUnauthorized());
        }

        @Test
        void expiredTokenIsRejected() throws Exception {
            Tokens tokens = tokensFrom(login(registered(), PASSWORD));
            jdbc.update("update auth.refresh_tokens set expiry_date = now() - interval '1 minute' where token_hash = ?",
                    sha256(tokens.refresh()));

            refresh(tokens.refresh()).andExpect(status().isUnauthorized());
        }
    }

    @Nested
    class Logout {

        @Test
        void endsOnlyThatSession() throws Exception {
            String email = registered();
            Tokens phone = tokensFrom(login(email, PASSWORD));
            Tokens laptop = tokensFrom(login(email, PASSWORD));

            logout(phone.refresh()).andExpect(status().isNoContent());

            refresh(phone.refresh()).andExpect(status().isUnauthorized());
            refresh(laptop.refresh()).andExpect(status().isOk());
        }

        @Test
        void retryingWithALoggedOutTokenDoesNotEndOtherSessions() throws Exception {
            String email = registered();
            Tokens phone = tokensFrom(login(email, PASSWORD));
            Tokens laptop = tokensFrom(login(email, PASSWORD));
            logout(phone.refresh());

            refresh(phone.refresh()).andExpect(status().isUnauthorized());
            refresh(phone.refresh()).andExpect(status().isUnauthorized());

            assertThat(reasonFor(laptop.refresh())).as("laptop session untouched").isNull();
            refresh(laptop.refresh()).andExpect(status().isOk());
        }

        @Test
        void isIdempotentForUnknownTokens() throws Exception {
            logout("never-issued").andExpect(status().isNoContent());
        }

        @Test
        void logoutAllEndsEverySession() throws Exception {
            String email = registered();
            Tokens phone = tokensFrom(login(email, PASSWORD));
            Tokens laptop = tokensFrom(login(email, PASSWORD));

            mvc.perform(post("/api/v1/auth/logout-all").header(HttpHeaders.AUTHORIZATION, "Bearer " + phone.access()))
                    .andExpect(status().isNoContent());

            refresh(phone.refresh()).andExpect(status().isUnauthorized());
            refresh(laptop.refresh()).andExpect(status().isUnauthorized());
            assertThat(reasonFor(laptop.refresh())).isEqualTo("LOGOUT_ALL");
        }
    }

    // ---- helpers -----------------------------------------------------------------------

    record Tokens(String access, String refresh) {
    }

    private static String uniqueEmail() {
        return "member-" + UUID.randomUUID() + "@example.com";
    }

    private String registered() throws Exception {
        String email = uniqueEmail();
        register(email, "0712345678").andExpect(status().isCreated());
        return email;
    }

    private ResultActions register(String email, String phone) throws Exception {
        return mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"%s","phoneNumber":"%s","password":"%s","firstName":"Jane","lastName":"Wanjiku"}"""
                .formatted(email, phone, PASSWORD)));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s"}""".formatted(email, password)));
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"refreshToken":"%s"}""".formatted(refreshToken)));
    }

    private ResultActions logout(String refreshToken) throws Exception {
        return mvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"refreshToken":"%s"}""".formatted(refreshToken)));
    }

    private static Tokens tokensFrom(ResultActions result) throws Exception {
        String body = result.andReturn().getResponse().getContentAsString();
        return new Tokens(JsonPath.read(body, "$.accessToken"), JsonPath.read(body, "$.refreshToken"));
    }

    private String reasonFor(String rawRefreshToken) {
        List<String> reasons = jdbc.queryForList(
                "select revoked_reason from auth.refresh_tokens where token_hash = ?", String.class, sha256(rawRefreshToken));
        assertThat(reasons).hasSize(1);
        return reasons.getFirst();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
