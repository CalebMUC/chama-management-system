package ke.chama.modules.auth.domain.event;

import ke.chama.shared.domain.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * The notification module emails {@code resetUrl} to the user. Only published for an
 * existing, enabled account; the API answers the same either way.
 * <p>
 * The URL contains a live secret, so it is masked in {@link #toString()}.
 */
public record PasswordResetRequested(UUID eventId,
                                     Instant occurredAt,
                                     UUID userId,
                                     String email,
                                     String firstName,
                                     String resetUrl,
                                     Instant expiresAt) implements DomainEvent {

    @Override
    public String toString() {
        return "PasswordResetRequested[eventId=" + eventId + ", userId=" + userId + ", resetUrl=***]";
    }
}
