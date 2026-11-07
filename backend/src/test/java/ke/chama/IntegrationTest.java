package ke.chama;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Full application + real PostgreSQL + MockMvc. Every integration test uses this exact
 * annotation so Spring caches one context and one container for the whole run.
 * <p>
 * The database is shared between tests: use unique data (a fresh chama per test via
 * {@link TestAccounts#onboard}), not cleanup. A platform administrator exists for every run.
 * Skipped when Docker is not running.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = {
        "chama.platform.admin.email=" + TestAccounts.PLATFORM_ADMIN_EMAIL,
        "chama.platform.admin.password=" + TestAccounts.PASSWORD
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
public @interface IntegrationTest {
}
