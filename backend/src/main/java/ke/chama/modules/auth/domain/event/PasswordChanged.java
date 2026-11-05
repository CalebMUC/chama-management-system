package ke.chama.modules.auth.domain.event;

import ke.chama.shared.domain.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * A password changed. The user is told by email, so a change they did not make is noticed
 * quickly.
 */
public record PasswordChanged(UUID eventId,
                              Instant occurredAt,
                              UUID userId,
                              String email,
                              String firstName,
                              Method method) implements DomainEvent {

    public enum Method {
        /** Through a forgot-password email link. */
        RESET_LINK,
        /** While logged in, by supplying the current password. */
        CHANGED_WHILE_LOGGED_IN
    }

    public static PasswordChanged of(UUID userId, String email, String firstName, Method method, Instant now) {
        return new PasswordChanged(UUID.randomUUID(), now, userId, email, firstName, method);
    }
}
