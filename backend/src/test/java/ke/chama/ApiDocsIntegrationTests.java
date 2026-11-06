package ke.chama;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The OpenAPI document is public outside 'prod' and carries the security rules. */
@IntegrationTest
class ApiDocsIntegrationTests {

    @Autowired
    MockMvc mvc;

    @Test
    void swaggerUiIsReachableWithoutAToken() throws Exception {
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    @Test
    void apiDocsDescribeBearerAuthAndChamaPermissions() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.security[0].bearerAuth").exists())
                // Public endpoints need no token...
                .andExpect(jsonPath("$.paths['/api/v1/auth/login'].post.security").value(empty()))
                // ...protected ones keep the global requirement.
                .andExpect(jsonPath("$.paths['/api/v1/auth/me'].get.security").doesNotExist())
                // Chama endpoints name the permission they need.
                .andExpect(jsonPath("$.paths['/api/v1/chamas/{chamaId}/settings'].put['x-chama-permission']")
                        .value("CHAMA_SETTINGS_MANAGE"))
                .andExpect(jsonPath("$.paths['/api/v1/chamas/{chamaId}/audit-log'].get['x-chama-permission']")
                        .value("AUDIT_LOG_VIEW"))
                .andExpect(jsonPath("$.paths.*.*.tags[*]", hasItem("Chamas")));
    }
}
