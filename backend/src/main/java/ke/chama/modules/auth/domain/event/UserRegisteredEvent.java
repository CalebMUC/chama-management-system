package ke.chama.modules.auth.domain.event;

import ke.chama.shared.domain.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when an account is created, in the same transaction. When {@code selfRegistered},
 * the member module records the person's application to join their chama, which an official
 * then approves.
 *
 * @param tenantId       the chama the account belongs to
 * @param selfRegistered false for accounts the system creates, e.g. a new chama's administrator
 */
public record UserRegisteredEvent(UUID eventId,
                                  Instant occurredAt,
                                  UUID tenantId,
                                  UUID userId,
                                  String email,
                                  String phoneNumber,
                                  String firstName,
                                  String lastName,
                                  boolean selfRegistered) implements DomainEvent {

    public static UserRegisteredEvent of(UUID tenantId, UUID userId, String email, String phoneNumber,
                                         String firstName, String lastName, boolean selfRegistered,
                                         Instant occurredAt) {
        return new UserRegisteredEvent(UUID.randomUUID(), occurredAt, tenantId, userId, email, phoneNumber,
                firstName, lastName, selfRegistered);
    }
}
