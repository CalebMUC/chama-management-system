package ke.chama.modules.chama.domain.event;

import ke.chama.shared.domain.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * A chama was founded. Handled synchronously, in the same transaction: the member module enrols
 * the founder as chairperson (a chama is never committed without someone who can manage it) and
 * the ledger creates the chart of accounts in {@code currency}.
 */
public record ChamaCreated(UUID eventId,
                           Instant occurredAt,
                           UUID chamaId,
                           String name,
                           String currency,
                           UUID founderUserId,
                           String founderEmail) implements DomainEvent {
}
