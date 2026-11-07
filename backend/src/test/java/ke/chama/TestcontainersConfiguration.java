package ke.chama;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real PostgreSQL for integration tests (same version as docker-compose), and a recording
 * email sender in place of SMTP.
 * <p>
 * The application's own connections run as the restricted {@code chama_app} role under
 * row-level security. Flyway, and {@link #ownerJdbc} for tests that must inspect or tamper with
 * the database directly, connect as the container's owner account instead.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));
    }

    @Bean
    DynamicPropertyRegistrar flywayOwnerConnection(PostgreSQLContainer postgres) {
        return registry -> {
            registry.add("spring.flyway.url", postgres::getJdbcUrl);
            registry.add("spring.flyway.user", postgres::getUsername);
            registry.add("spring.flyway.password", postgres::getPassword);
        };
    }

    /** Bypasses row-level security (owner account). Only for setting up or checking test data. */
    @Bean
    OwnerJdbc ownerJdbc(PostgreSQLContainer postgres) {
        return new OwnerJdbc(new JdbcTemplate(
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())));
    }

    @Bean
    @Primary
    RecordingEmailSender recordingEmailSender() {
        return new RecordingEmailSender();
    }

    /** A JdbcTemplate on the owner account, kept apart from the application's JdbcTemplate bean. */
    public record OwnerJdbc(JdbcTemplate jdbc) {
    }
}
