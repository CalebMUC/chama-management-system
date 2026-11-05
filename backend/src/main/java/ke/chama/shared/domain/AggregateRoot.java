package ke.chama.shared.domain;

import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Transient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Entry point of a consistency boundary (Loan, Contribution, Membership, ...).
 * <p>
 * Domain methods record events with {@link #registerEvent}. The application service publishes
 * them after saving:
 * <pre>{@code
 *   loan.approve(approverId, clock);
 *   loanRepository.save(loan);
 *   loan.pullDomainEvents().forEach(eventPublisher::publishEvent);
 * }</pre>
 * Publishing inside the transaction lets the Spring Modulith event registry store the event
 * with the business change (the outbox), so a crash cannot lose it.
 */
@MappedSuperclass
public abstract class AggregateRoot extends AuditableEntity {

    @Transient
    private final List<DomainEvent> domainEvents = new ArrayList<>();

    protected void registerEvent(DomainEvent event) {
        domainEvents.add(event);
    }

    /** Returns the pending events and clears them, so each event is published once. */
    public List<DomainEvent> pullDomainEvents() {
        List<DomainEvent> events = List.copyOf(domainEvents);
        domainEvents.clear();
        return events;
    }

    public List<DomainEvent> peekDomainEvents() {
        return Collections.unmodifiableList(domainEvents);
    }
}
