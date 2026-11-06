package ke.chama.modules.auth.domain.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Server-side record of one login session's refresh token.
 * <p>
 * Only a SHA-256 hash of the token is stored, so a leaked database row cannot be used to log in.
 * Each refresh revokes the presented token (ROTATED) and issues a new one. Revoked rows are
 * kept with their reason so reuse can be detected and investigated.
 */
@Entity
@Table(name = "refresh_tokens", schema = "auth")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Many refresh tokens can belong to one user: one per active session (phone, laptop).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** Hex SHA-256 of the opaque token handed to the client. */
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expiry_date", nullable = false)
    private Instant expiryDate;

    @Column(nullable = false)
    private boolean revoked = false;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoked_reason", length = 30)
    private RevocationReason revokedReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected RefreshToken() {
    }

    public RefreshToken(User user, String tokenHash, Instant expiryDate) {
        this.user = user;
        this.tokenHash = tokenHash;
        this.expiryDate = expiryDate;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiryDate);
    }

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getExpiryDate() {
        return expiryDate;
    }

    public boolean isRevoked() {
        return revoked;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public RevocationReason getRevokedReason() {
        return revokedReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
