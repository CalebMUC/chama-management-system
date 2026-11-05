package ke.chama.modules.auth.domain.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Server-side record of an issued refresh token, so tokens can be revoked.
 * <p>
 * Phase 1 change: store only a SHA-256 hash of the token (a leaked database row must not be
 * usable as a login) and issue a new token on every refresh, revoking all of the user's tokens
 * if an already-used one is presented again.
 */
@Entity
@Table(name = "refresh_tokens", schema = "auth")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Many refresh tokens can belong to one user: one per active session (phone, laptop).
     * All of them are revoked on logout.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, unique = true)
    private String token;

    @Column(name = "expiry_date", nullable = false)
    private Instant expiryDate;

    @Column(nullable = false)
    private boolean revoked = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected RefreshToken() {
    }

    public RefreshToken(User user, String token, Instant expiryDate) {
        this.user = user;
        this.token = token;
        this.expiryDate = expiryDate;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiryDate);
    }

    public boolean isUsable(Instant now) {
        return !revoked && !isExpired(now);
    }

    public void revoke() {
        this.revoked = true;
    }

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getToken() {
        return token;
    }

    public Instant getExpiryDate() {
        return expiryDate;
    }

    public boolean isRevoked() {
        return revoked;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
