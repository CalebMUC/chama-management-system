package ke.chama.modules.contribution.domain.event;

import ke.chama.shared.domain.DomainEvent;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A contribution was recorded. The notification module emails the member a receipt. */
public record ContributionRecorded(UUID eventId,
                                   Instant occurredAt,
                                   UUID chamaId,
                                   String chamaName,
                                   UUID paymentId,
                                   UUID memberUserId,
                                   String memberEmail,
                                   String memberFirstName,
                                   long amountMinor,
                                   String currency,
                                   String method,
                                   String reference,
                                   LocalDate paidOn,
                                   long savingsBalanceMinor,
                                   long arrearsMinor) implements DomainEvent {
}
