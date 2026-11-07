package ke.chama.modules.penalty.domain.event;

import ke.chama.shared.domain.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/** A member was penalised (late contribution or fine). The notification module emails them. */
public record PenaltyAssessed(UUID eventId,
                              Instant occurredAt,
                              UUID chamaId,
                              String chamaName,
                              UUID penaltyId,
                              UUID memberUserId,
                              String memberEmail,
                              String memberFirstName,
                              String kind,
                              String description,
                              long amountMinor,
                              String currency) implements DomainEvent {
}
