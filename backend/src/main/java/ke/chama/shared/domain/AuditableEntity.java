package ke.chama.shared.domain;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * Base for persistent entities: UTC timestamps plus an optimistic-locking version.
 * <p>
 * {@code @Version} matters for money: if two treasurers approve the same loan at the same
 * moment, the second save fails with a conflict instead of silently overwriting the first.
 * <p>
 * "Who did it" is deliberately not tracked here. Accountable actions carry explicit domain
 * fields ({@code recordedBy}, {@code approvedBy}), and the audit log records every actor.
 * <p>
 * Expected columns: {@code created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ, version BIGINT}.
 */
@MappedSuperclass
public abstract class AuditableEntity {

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
