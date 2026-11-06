package ke.chama.modules.chama.domain.event;

import ke.chama.shared.domain.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * A chama was founded. The member module handles this synchronously, in the same transaction,
 * to enrol the founder as chairperson: a chama is never committed without someone who can
 * manage it.
 */
public record ChamaCreated(UUID eventId,
                           Instant occurredAt,
                           UUID chamaId,
                           String name,
                           UUID founderUserId,
                           String founderEmail) implements DomainEvent {
}
