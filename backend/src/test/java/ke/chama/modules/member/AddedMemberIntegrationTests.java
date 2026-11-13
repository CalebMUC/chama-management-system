package ke.chama.modules.member;

import com.jayway.jsonpath.JsonPath;
import ke.chama.IntegrationTest;
import ke.chama.RecordingEmailSender;
import ke.chama.TestAccounts;
import ke.chama.TestAccounts.Account;
import ke.chama.TestAccounts.TestChama;
import ke.chama.shared.tenancy.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Officials adding members directly, with a temporary password and KYC details, over HTTP. */
@IntegrationTest
class AddedMemberIntegrationTests {

    static final String TEMPORARY_PASSWORD = "Karibu2026";

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
    void anAdminAddsAnActiveMemberWhoMustChooseTheirOwnPasswordFirst() throws Exception {
        TestChama chama = accounts.onboard("Achieng");
        Account admin = accounts.member(chama, "Ann");
        mvc.perform(chama.chair().put("/api/v1/members/{m}/roles", "{\"roles\":[\"ADMIN\"]}", accounts.membershipId(admin)))
                .andExpect(status().isOk());
        String email = TestAccounts.uniqueEmail("wanjiru");

        String added = mvc.perform(admin.post("/api/v1/members", addMember("Wanjiru", email, "12 345 678")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.memberNumber").value(3))
                .andExpect(jsonPath("$.data.firstName").value("Wanjiru"))
                .andExpect(jsonPath("$.data.phoneNumber").value("+254712345678"))
                .andExpect(jsonPath("$.data.emailVerified").value(true))
                .andReturn().getResponse().getContentAsString();
        UUID membershipId = UUID.fromString(JsonPath.read(added, "$.data.membershipId"));

        mvc.perform(admin.get("/api/v1/members/{m}/kyc", membershipId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.idType").value("NATIONAL_ID"))
                .andExpect(jsonPath("$.data.idNumber").value("12345678"))
                .andExpect(jsonPath("$.data.dateOfBirth").value("1990-04-12"))
                .andExpect(jsonPath("$.data.gender").value("FEMALE"))
                .andExpect(jsonPath("$.data.county").value("Nairobi"))
                .andExpect(jsonPath("$.data.town").doesNotExist());
        assertThat(emails.sentTo(email)).isEmpty();

        // Signed in with the temporary password: nothing but the profile and the password change.
        String token = login(chama.code(), email, TEMPORARY_PASSWORD);
        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mustChangePassword").value(true));
        mvc.perform(get("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AUTH_PASSWORD_CHANGE_REQUIRED"));

        String changed = mvc.perform(post("/api/v1/auth/password/change").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(TEMPORARY_PASSWORD, TestAccounts.PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String fresh = JsonPath.read(changed, "$.data.accessToken");
        mvc.perform(get("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + fresh))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.membership.status").value("ACTIVE"));
        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + fresh))
                .andExpect(jsonPath("$.data.mustChangePassword").value(false));
        accounts.login(chama.code(), email);
    }

    @Test
    void rejectsDuplicatesMinorsAndMissingDetails() throws Exception {
        TestChama chama = accounts.onboard("Kamau");
        Account chair = chama.chair();
        String email = TestAccounts.uniqueEmail("otieno");
        mvc.perform(chair.post("/api/v1/members", addMember("Otieno", email, "23456789")))
                .andExpect(status().isCreated());

        mvc.perform(chair.post("/api/v1/members", addMember("Otieno", email.toUpperCase(), "34567890")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MEMBER_ALREADY_EXISTS"));
        mvc.perform(chair.post("/api/v1/members", addMember("Akinyi", TestAccounts.uniqueEmail("akinyi"), "23-456-789")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MEMBER_ID_NUMBER_TAKEN"));

        String minor = addMember("Kevo", TestAccounts.uniqueEmail("kevo"), "45678901")
                .replace("1990-04-12", LocalDate.now().minusYears(17).toString());
        mvc.perform(chair.post("/api/v1/members", minor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MEMBER_UNDER_AGE"));

        String noCounty = addMember("Njeri", TestAccounts.uniqueEmail("njeri"), "56789012")
                .replace("\"county\":\"Nairobi\"", "\"county\":\"\"");
        mvc.perform(chair.post("/api/v1/members", noCounty))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields[*].field").value(hasItem("kyc.county")));
    }

    @Test
    void officialsKeepKycForExistingMembersAndPlainMembersCannotAddOrSeeIt() throws Exception {
        TestChama chama = accounts.onboard("Mutua");
        Account member = accounts.member(chama, "Peter");
        UUID membershipId = accounts.membershipId(member);

        mvc.perform(chama.chair().get("/api/v1/members/{m}/kyc", membershipId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());
        mvc.perform(chama.chair().put("/api/v1/members/{m}/kyc", """
                        {"idType":"PASSPORT","idNumber":"ak 123456","dateOfBirth":"1985-01-31","gender":"MALE",
                         "county":"Kisumu","town":"Kisumu","physicalAddress":"Milimani, House 4"}""", membershipId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.idNumber").value("AK123456"))
                .andExpect(jsonPath("$.data.physicalAddress").value("Milimani, House 4"));

        mvc.perform(member.post("/api/v1/members", addMember("Mary", TestAccounts.uniqueEmail("mary"), "67890123")))
                .andExpect(status().isForbidden());
        mvc.perform(member.get("/api/v1/members/{m}/kyc", membershipId))
                .andExpect(status().isForbidden());
    }

    private static String addMember(String firstName, String email, String idNumber) {
        return """
                {"firstName":"%s","lastName":"Test","email":"%s","phoneNumber":"0712 345 678","temporaryPassword":"%s",
                 "kyc":{"idType":"NATIONAL_ID","idNumber":"%s","dateOfBirth":"1990-04-12","gender":"FEMALE",
                        "county":"Nairobi","town":"  "}}""".formatted(firstName, email, TEMPORARY_PASSWORD, idNumber);
    }

    private String login(String chamaCode, String email, String password) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").header(TenantContext.HEADER, chamaCode)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.accessToken");
    }
}
