package ke.chama.modules.loan.domain.event;

import ke.chama.shared.domain.DomainEvent;

import java.time.LocalDate;
import java.time.Instant;
import java.util.UUID;

/**
 * A loan was approved, rejected, disbursed or fully repaid. The notification module tells the borrower.
 *
 * @param status          APPROVED, REJECTED, ACTIVE (disbursed) or REPAID
 * @param reason          the rejection reason, for REJECTED
 * @param firstDueDate    the first instalment's due date, for ACTIVE
 * @param instalmentMinor the regular monthly instalment, for ACTIVE
 */
public record LoanStatusChanged(UUID eventId,
                                Instant occurredAt,
                                UUID chamaId,
                                String chamaName,
                                UUID loanId,
                                UUID borrowerUserId,
                                String borrowerEmail,
                                String borrowerFirstName,
                                String status,
                                long principalMinor,
                                long totalDueMinor,
                                String currency,
                                String reason,
                                LocalDate firstDueDate,
                                long instalmentMinor) implements DomainEvent {
}
