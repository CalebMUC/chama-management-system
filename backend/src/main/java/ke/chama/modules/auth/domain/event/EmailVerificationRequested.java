package ke.chama.modules.auth.domain.event;

import ke.chama.shared.domain.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * The notification module emails {@code verificationUrl} to the user.
 * <p>
 * The URL contains a live secret, so it is masked in {@link #toString()} and the event table
 * deletes completed events ({@code spring.modulith.events.completion-mode: delete}).
 */
public record EmailVerificationRequested(UUID eventId,
                                         Instant occurredAt,
                                         UUID userId,
                                         String email,
                                         String firstName,
                                         String verificationUrl,
                                         Instant expiresAt) implements DomainEvent {

    @Override
    public String toString() {
        return "EmailVerificationRequested[eventId=" + eventId + ", userId=" + userId + ", verificationUrl=***]";
    }
}
