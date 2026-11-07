package ke.chama.modules.auth.domain.event;

import ke.chama.shared.domain.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * An account was created for someone (e.g. a member imported from the chama's spreadsheets), who
 * must now choose a password. The notification module emails {@code setupUrl}; setting the password
 * there also confirms the email address.
 * <p>
 * The URL contains a live secret, so it is masked in {@link #toString()}.
 */
public record AccountSetupRequested(UUID eventId,
                                    Instant occurredAt,
                                    UUID userId,
                                    String email,
                                    String firstName,
                                    String chamaName,
                                    String chamaCode,
                                    String setupUrl,
                                    Instant expiresAt) implements DomainEvent {

    @Override
    public String toString() {
        return "AccountSetupRequested[eventId=" + eventId + ", userId=" + userId + ", setupUrl=***]";
    }
}
