package ke.chama.modules.loan.domain.event;

import ke.chama.shared.domain.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/** A member was asked to guarantee a loan. The notification module emails them. */
public record LoanGuarantorRequested(UUID eventId,
                                     Instant occurredAt,
                                     UUID chamaId,
                                     String chamaName,
                                     UUID loanId,
                                     UUID guarantorUserId,
                                     String guarantorEmail,
                                     String guarantorFirstName,
                                     String borrowerName,
                                     long principalMinor,
                                     String currency,
                                     int termMonths,
                                     String purpose) implements DomainEvent {
}
