package ke.chama.modules.transaction.domain.event;

import ke.chama.shared.domain.DomainEvent;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** An entry was posted. Reports and notifications (e.g. "your savings balance is now ...") react to this. */
public record LedgerEntryPosted(UUID eventId,
                                Instant occurredAt,
                                UUID chamaId,
                                UUID entryId,
                                long entryNumber,
                                LocalDate entryDate,
                                String sourceType,
                                String sourceId,
                                long totalMinor) implements DomainEvent {
}
