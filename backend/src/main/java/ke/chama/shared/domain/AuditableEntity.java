package ke.chama.shared.domain;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Base for persistent entities: UTC timestamps plus an optimistic-locking version.
 * <p>
 * {@code @Version} matters for money: if two treasurers approve the same loan at the same
 * moment, the second save fails with a conflict instead of silently overwriting the first.
 * <p>
 * Timestamps are set in JPA callbacks, which run as soon as the entity is saved, so they are
 * already filled in when a response is built in the same request. (Hibernate's
 * {@code @CreationTimestamp} only fills them at flush, typically commit.) They are truncated to
 * microseconds to match PostgreSQL's precision.
 * <p>
 * "Who did it" is deliberately not tracked here. Accountable actions carry explicit domain
 * fields ({@code recordedBy}, {@code approvedBy}), and the audit log records every actor.
 * <p>
 * Expected columns: {@code created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ, version BIGINT}.
 */
@MappedSuperclass
public abstract class AuditableEntity {

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Boxed on purpose: Spring Data treats an entity whose version is {@code null} as new.
     * That lets aggregates assign their own UUID in the constructor (so domain events can
     * carry the ID before saving) and still be INSERTed rather than merged.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }
}
