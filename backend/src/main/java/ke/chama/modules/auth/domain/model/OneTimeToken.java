package ke.chama.modules.auth.domain.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A single-use secret sent by email (verification or password-reset link).
 * Only its SHA-256 hash is stored. Requesting a new link invalidates older unused ones,
 * so only the latest email works.
 */
@Entity
@Table(name = "one_time_tokens", schema = "auth")
public class OneTimeToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TokenPurpose purpose;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "invalidated_at")
    private Instant invalidatedAt;

    /** Required by JPA. */
    protected OneTimeToken() {
    }

    public OneTimeToken(User user, TokenPurpose purpose, String tokenHash, Instant createdAt, Instant expiresAt) {
        this.user = user;
        this.purpose = purpose;
        this.tokenHash = tokenHash;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public boolean isUsableFor(TokenPurpose expectedPurpose, Instant now) {
        return purpose == expectedPurpose && usedAt == null && invalidatedAt == null && now.isBefore(expiresAt);
    }

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public TokenPurpose getPurpose() {
        return purpose;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }
}
