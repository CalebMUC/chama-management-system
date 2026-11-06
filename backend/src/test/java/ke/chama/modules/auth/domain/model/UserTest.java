package ke.chama.modules.auth.domain.model;

import java.util.UUID;
import ke.chama.modules.auth.domain.event.UserRegisteredEvent;
import ke.chama.shared.domain.exception.DomainValidationException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserTest {

    private static final UUID CHAMA = UUID.randomUUID();

    private static final Instant NOW = Instant.parse("2026-11-05T09:00:00Z");

    @Test
    void registrationNormalizesContactDetailsAndRecordsEvent() {
        Role userRole = new Role();
        userRole.setName(Role.USER);

        User user = User.register(CHAMA, "  Jane.Wanjiku@Example.COM ", "0712 345 678", "{bcrypt}hash",
                " Jane ", "Wanjiku", userRole, true, NOW);

        assertThat(user.getId()).isNotNull();
        assertThat(user.getTenantId()).isEqualTo(CHAMA);
        assertThat(user.getEmail()).isEqualTo("jane.wanjiku@example.com");
        assertThat(user.getPhoneNumber()).isEqualTo("+254712345678");
        assertThat(user.getFirstName()).isEqualTo("Jane");
        assertThat(user.getRoles()).containsExactly(userRole);
        assertThat(user.isEnabled()).isTrue();

        assertThat(user.pullDomainEvents()).singleElement()
                .isInstanceOfSatisfying(UserRegisteredEvent.class, event -> {
                    assertThat(event.userId()).isEqualTo(user.getId());
                    assertThat(event.tenantId()).isEqualTo(CHAMA);
                    assertThat(event.selfRegistered()).isTrue();
                    assertThat(event.email()).isEqualTo("jane.wanjiku@example.com");
                    assertThat(event.occurredAt()).isEqualTo(NOW);
                });
        assertThat(user.pullDomainEvents()).as("events are handed out once").isEmpty();
    }

    @Test
    void newUsersAreTreatedAsUnsaved() {
        User user = new User(CHAMA, "a@b.co", "0712345678", "hash", "A", "B");

        assertThat(user.getVersion()).as("null version tells Spring Data to INSERT, not merge").isNull();
    }

    @Test
    void rejectsMissingNames() {
        assertThatThrownBy(() -> new User(CHAMA, "a@b.co", "0712345678", "hash", " ", "B"))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void rejectsMalformedEmail() {
        assertThatThrownBy(() -> new User(CHAMA, "not-an-email", "0712345678", "hash", "A", "B"))
                .isInstanceOf(DomainValidationException.class)
                .extracting("code").isEqualTo("AUTH_INVALID_EMAIL");
    }
}
