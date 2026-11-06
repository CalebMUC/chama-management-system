package ke.chama.modules.auth.domain.event;

import ke.chama.shared.domain.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Published after a user registers. The member module listens so it can link pending chama
 * invitations to the new user.
 */
public record UserRegisteredEvent(UUID eventId,
                                  Instant occurredAt,
                                  UUID userId,
                                  String email,
                                  String phoneNumber,
                                  String firstName,
                                  String lastName) implements DomainEvent {

    public static UserRegisteredEvent of(UUID userId, String email, String phoneNumber,
                                         String firstName, String lastName, Instant occurredAt) {
        return new UserRegisteredEvent(UUID.randomUUID(), occurredAt, userId, email, phoneNumber, firstName, lastName);
    }
}
