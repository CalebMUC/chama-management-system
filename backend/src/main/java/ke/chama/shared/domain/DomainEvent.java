package ke.chama.shared.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Something that happened in the domain that other modules may react to.
 * <p>
 * Events are the contract between modules, so treat them like a public API:
 * <ul>
 *   <li>Records only, carrying IDs and values (never entities).</li>
 *   <li>{@code eventId} lets consumers de-duplicate. Every listener must be idempotent,
 *       because once a module is extracted the same event may arrive twice over RabbitMQ.</li>
 *   <li>Add fields rather than renaming or removing them.</li>
 * </ul>
 */
public interface DomainEvent {

    UUID eventId();

    Instant occurredAt();
}
