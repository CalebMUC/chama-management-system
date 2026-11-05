package ke.chama;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots the whole application against a real PostgreSQL. This runs every Flyway migration and
 * then Hibernate's {@code ddl-auto: validate}, so any mismatch between an entity and its table
 * (like the old {@code refresh_token} vs {@code refresh_tokens}) fails here, not in production.
 * <p>
 * Skipped when Docker is not running.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class ChamaApplicationTests {

    @Test
    void contextLoads() {
    }
}
