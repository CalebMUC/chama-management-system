package ke.chama.modules.chama.domain.event;

import ke.chama.shared.domain.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/** Settings changed. Later modules (e.g. contribution cycles) can react to the new rules. */
public record ChamaSettingsChanged(UUID eventId,
                                   Instant occurredAt,
                                   UUID chamaId,
                                   UUID changedBy) implements DomainEvent {
}
