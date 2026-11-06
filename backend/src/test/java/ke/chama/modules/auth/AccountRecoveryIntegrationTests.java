package ke.chama.modules.auth;

import com.jayway.jsonpath.JsonPath;
import ke.chama.IntegrationTest;
import ke.chama.RecordingEmailSender;
import ke.chama.modules.notification.application.port.out.EmailMessage;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Email verification, forgot/reset password and change password, end to end including the emails. */
@IntegrationTest
class AccountRecoveryIntegrationTests {

    private static final String PASSWORD = "Sacco2026pass";
    private static final String NEW_PASSWORD = "Harambee2027";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RecordingEmailSender emails;

    @Nested
    class EmailVerification {

        @Test
        void registrationSendsALinkThatVerifiesTheEmail() throws Exception {
            String email = registered();
            EmailMessage message = emails.awaitEmail(email, s -> s.contains("Verify"));
            assertThat(message.body()).contains("http://localhost:3000/verify-email?token=");

            post("/api/v1/auth/email/verify", "{\"token\":\"%s\"}".formatted(RecordingEmailSender.tokenFrom(message)))
                    .andExpect(status().isNoContent());

            String access = accessToken(email, PASSWORD);
            mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
                    .andExpect(jsonPath("$.emailVerified").value(true));
        }

        @Test
        void aLinkWorksOnlyOnce() throws Exception {
            String email = registered();
            String token = RecordingEmailSender.tokenFrom(emails.awaitEmail(email, s -> s.contains("Verify")));

            post("/api/v1/auth/email/verify", "{\"token\":\"%s\"}".formatted(token)).andExpect(status().isNoContent());
            post("/api/v1/auth/email/verify", "{\"token\":\"%s\"}".formatted(token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("AUTH_LINK_INVALID"));
        }

        @Test
        void resendingReplacesTheOldLink() throws Exception {
            String email = registered();
            String first = RecordingEmailSender.tokenFrom(emails.awaitEmail(email, s -> s.contains("Verify")));
            allowImmediateResend(email);

            post("/api/v1/auth/email/verify/resend", "{\"email\":\"%s\"}".formatted(email)).andExpect(status().isAccepted());
            awaitCount(email, "Verify", 2);
            String second = RecordingEmailSender.tokenFrom(emails.awaitEmail(email, s -> s.contains("Verify")));

            assertThat(second).isNotEqualTo(first);
            post("/api/v1/auth/email/verify", "{\"token\":\"%s\"}".formatted(first)).andExpect(status().isBadRequest());
            post("/api/v1/auth/email/verify", "{\"token\":\"%s\"}".formatted(second)).andExpect(status().isNoContent());
        }

        @Test
        void resendIsThrottled() throws Exception {
            String email = registered();
            emails.awaitEmail(email, s -> s.contains("Verify"));

            post("/api/v1/auth/email/verify/resend", "{\"email\":\"%s\"}".formatted(email)).andExpect(status().isAccepted());
            Thread.sleep(300);

            assertThat(emails.sentTo(email)).filteredOn(m -> m.subject().contains("Verify")).hasSize(1);
        }

        @Test
        void resendForUnknownEmailLooksTheSame() throws Exception {
            post("/api/v1/auth/email/verify/resend", "{\"email\":\"%s\"}".formatted(uniqueEmail()))
                    .andExpect(status().isAccepted());
        }
    }

    @Nested
    class ForgotAndResetPassword {

        @Test
        void resetLinkSetsANewPasswordAndEndsEverySession() throws Exception {
            String email = registered();
            String oldRefresh = JsonPath.read(login(email, PASSWORD).andReturn().getResponse().getContentAsString(), "$.refreshToken");

            post("/api/v1/auth/password/forgot", "{\"email\":\"%s\"}".formatted(email)).andExpect(status().isAccepted());
            EmailMessage message = emails.awaitEmail(email, s -> s.contains("Reset"));
            assertThat(message.body()).contains("http://localhost:3000/reset-password?token=");

            post("/api/v1/auth/password/reset", """
                    {"token":"%s","newPassword":"%s"}""".formatted(RecordingEmailSender.tokenFrom(message), NEW_PASSWORD))
                    .andExpect(status().isNoContent());

            login(email, PASSWORD).andExpect(status().isUnauthorized());
            login(email, NEW_PASSWORD).andExpect(status().isOk());
            post("/api/v1/auth/refresh", "{\"refreshToken\":\"%s\"}".formatted(oldRefresh)).andExpect(status().isUnauthorized());
            assertThat(jdbc.queryForObject("""
                    select revoked_reason from auth.refresh_tokens t join auth.users u on u.id = t.user_id
                     where u.email = ? order by t.created_at limit 1""", String.class, email)).isEqualTo("PASSWORD_CHANGED");

            emails.awaitEmail(email, s -> s.contains("password was changed"));
        }

        @Test
        void resetAlsoVerifiesTheEmailAndLiftsTheLockout() throws Exception {
            String email = registered();
            for (int i = 0; i < 5; i++) {
                login(email, "Wrong2026pass");
            }
            login(email, PASSWORD).andExpect(status().isTooManyRequests());

            post("/api/v1/auth/password/forgot", "{\"email\":\"%s\"}".formatted(email));
            String token = RecordingEmailSender.tokenFrom(emails.awaitEmail(email, s -> s.contains("Reset")));
            post("/api/v1/auth/password/reset", """
                    {"token":"%s","newPassword":"%s"}""".formatted(token, NEW_PASSWORD)).andExpect(status().isNoContent());

            login(email, NEW_PASSWORD).andExpect(status().isOk());
            assertThat(jdbc.queryForObject("select email_verified_at is not null from auth.users where email = ?",
                    Boolean.class, email)).isTrue();
        }

        @Test
        void aWeakNewPasswordDoesNotUseUpTheLink() throws Exception {
            String email = registered();
            post("/api/v1/auth/password/forgot", "{\"email\":\"%s\"}".formatted(email));
            String token = RecordingEmailSender.tokenFrom(emails.awaitEmail(email, s -> s.contains("Reset")));

            post("/api/v1/auth/password/reset", """
                    {"token":"%s","newPassword":"short"}""".formatted(token)).andExpect(status().isBadRequest());
            post("/api/v1/auth/password/reset", """
                    {"token":"%s","newPassword":"%s"}""".formatted(token, NEW_PASSWORD)).andExpect(status().isNoContent());
        }

        @Test
        void forgotPasswordForUnknownEmailLooksTheSameAndSendsNothing() throws Exception {
            String ghost = uniqueEmail();
            post("/api/v1/auth/password/forgot", "{\"email\":\"%s\"}".formatted(ghost)).andExpect(status().isAccepted());
            Thread.sleep(300);

            assertThat(emails.sentTo(ghost)).isEmpty();
        }

        @Test
        void forgedTokenIsRejected() throws Exception {
            post("/api/v1/auth/password/reset", """
                    {"token":"forged-token","newPassword":"%s"}""".formatted(NEW_PASSWORD))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("AUTH_LINK_INVALID"));
        }
    }

    @Nested
    class ChangePassword {

        @Test
        void requiresTheCurrentPasswordAndKeepsThisDeviceLoggedIn() throws Exception {
            String email = registered();
            String body = login(email, PASSWORD).andReturn().getResponse().getContentAsString();
            String access = JsonPath.read(body, "$.accessToken");
            String otherDeviceRefresh = JsonPath.read(login(email, PASSWORD).andReturn().getResponse().getContentAsString(),
                    "$.refreshToken");

            changePassword(access, "Wrong2026pass", NEW_PASSWORD)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("AUTH_CURRENT_PASSWORD_INCORRECT"));

            String newRefresh = JsonPath.read(changePassword(access, PASSWORD, NEW_PASSWORD)
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(), "$.refreshToken");

            post("/api/v1/auth/refresh", "{\"refreshToken\":\"%s\"}".formatted(otherDeviceRefresh)).andExpect(status().isUnauthorized());
            post("/api/v1/auth/refresh", "{\"refreshToken\":\"%s\"}".formatted(newRefresh)).andExpect(status().isOk());
            login(email, NEW_PASSWORD).andExpect(status().isOk());
            emails.awaitEmail(email, s -> s.contains("password was changed"));
        }

        @Test
        void requiresAuthentication() throws Exception {
            post("/api/v1/auth/password/change", """
                    {"currentPassword":"%s","newPassword":"%s"}""".formatted(PASSWORD, NEW_PASSWORD))
                    .andExpect(status().isUnauthorized());
        }

        private ResultActions changePassword(String accessToken, String current, String next) throws Exception {
            return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .post("/api/v1/auth/password/change")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"currentPassword":"%s","newPassword":"%s"}""".formatted(current, next)));
        }
    }

    // ---- helpers -----------------------------------------------------------------------

    private static String uniqueEmail() {
        return "member-" + UUID.randomUUID() + "@example.com";
    }

    private String registered() throws Exception {
        String email = uniqueEmail();
        post("/api/v1/auth/register", """
                {"email":"%s","phoneNumber":"0712345678","password":"%s","firstName":"Jane","lastName":"Wanjiku"}"""
                .formatted(email, PASSWORD)).andExpect(status().isCreated());
        return email;
    }

    private ResultActions login(String email, String password) throws Exception {
        return post("/api/v1/auth/login", """
                {"email":"%s","password":"%s"}""".formatted(email, password));
    }

    private String accessToken(String email, String password) throws Exception {
        return JsonPath.read(login(email, password).andReturn().getResponse().getContentAsString(), "$.accessToken");
    }

    private ResultActions post(String url, String json) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    /** Back-dates earlier links so the resend throttle does not apply. */
    private void allowImmediateResend(String email) {
        jdbc.update("""
                update auth.one_time_tokens set created_at = created_at - interval '5 minutes'
                 where user_id = (select id from auth.users where email = ?)""", email);
    }

    private void awaitCount(String email, String subjectPart, int count) throws InterruptedException {
        for (int i = 0; i < 200 && emails.sentTo(email).stream().filter(m -> m.subject().contains(subjectPart)).count() < count; i++) {
            Thread.sleep(50);
        }
    }
}
