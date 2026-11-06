package ke.chama.modules.auth.domain.model;

import org.hibernate.annotations.TenantId;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One login attempt. Append-only: drives lockout, and lets a member (or support) answer
 * "who tried to log in as me, and from where?".
 */
@Entity
@Table(name = "login_attempts", schema = "auth")
public class LoginAttempt {

    static final int MAX_USER_AGENT_LENGTH = 512;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Normalized email as typed; recorded even when no such user exists. */
    /** The chama this row belongs to. Set by Hibernate from the current tenant; never changes. */
    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String email;

    @Column(name = "user_id")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private LoginOutcome outcome;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = MAX_USER_AGENT_LENGTH)
    private String userAgent;

    @Column(name = "attempted_at", nullable = false)
    private Instant attemptedAt;

    /** Required by JPA. */
    protected LoginAttempt() {
    }

    public LoginAttempt(String email, UUID userId, LoginOutcome outcome,
                        String ipAddress, String userAgent, Instant attemptedAt) {
        this.email = email;
        this.userId = userId;
        this.outcome = outcome;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent != null && userAgent.length() > MAX_USER_AGENT_LENGTH
                ? userAgent.substring(0, MAX_USER_AGENT_LENGTH)
                : userAgent;
        this.attemptedAt = attemptedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getEmail() {
        return email;
    }

    public UUID getUserId() {
        return userId;
    }

    public LoginOutcome getOutcome() {
        return outcome;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public Instant getAttemptedAt() {
        return attemptedAt;
    }
}
