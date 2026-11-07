package ke.chama.modules.member.domain.event;

import ke.chama.shared.domain.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/** The notification module emails the invitee a link to register with {@code chamaCode}. */
public record MemberInvited(UUID eventId,
                            Instant occurredAt,
                            UUID chamaId,
                            String chamaName,
                            String chamaCode,
                            String email,
                            String invitedByName) implements DomainEvent {
}
