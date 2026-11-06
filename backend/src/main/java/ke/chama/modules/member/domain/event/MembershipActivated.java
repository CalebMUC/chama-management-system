package ke.chama.modules.member.domain.event;

import ke.chama.shared.domain.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * A member was approved and is now active. Later modules react to this, e.g. contributions
 * opens a savings account for the member.
 */
public record MembershipActivated(UUID eventId,
                                  Instant occurredAt,
                                  UUID chamaId,
                                  String chamaName,
                                  UUID membershipId,
                                  UUID userId,
                                  String email,
                                  String firstName,
                                  int memberNumber) implements DomainEvent {
}
