package ke.chama.modules.auth.domain.model;

import org.hibernate.annotations.TenantId;
import jakarta.persistence.*;
import ke.chama.modules.auth.domain.event.UserRegisteredEvent;
import ke.chama.shared.domain.AggregateRoot;
import ke.chama.shared.domain.PhoneNumber;
import ke.chama.shared.domain.exception.DomainValidationException;

import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * A login identity. Not the same thing as a chama member.
 * <p>
 * One user can belong to several chamas with a different role in each (Treasurer in one,
 * Member in another). Chama roles therefore live on the Membership in the member module,
 * not here. {@link #roles} holds platform roles only: ROLE_USER for everyone, ROLE_ADMIN
 * for system administrators.
 */
@Entity
@Table(name = "users", schema = "auth")
public class User extends AggregateRoot {

    /** Assigned on creation (not by the database) so the registration event can carry it. */
    @Id
    private UUID id;

    /**
     * The chama this user belongs to; every user belongs to exactly one. Platform administrators
     * belong to the platform tenant. Hibernate checks it matches the current tenant on insert.
     */
    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    /**
     * Stored trimmed and lower-cased, so "Jane@Mail.com" and "jane@mail.com" are one account.
     * Unique within the chama: the same person may hold separate accounts in different chamas.
     */
    @Column(nullable = false)
    private String email;

    /** E.164 format (+2547XXXXXXXX). Needed for M-Pesa and SMS notifications. */
    @Column(name = "phone_number", nullable = false)
    private String phoneNumber;

    /** Password hash (BCrypt via Spring's DelegatingPasswordEncoder). Never the raw password. */
    @Column(nullable = false)
    private String password;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    @Column(name = "is_enabled", nullable = false)
    private boolean isEnabled = true;

    /** Set when the user proves they own the email (verification link or password reset link). */
    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    /** Last password change. Failed logins before this moment no longer count toward lockout. */
    @Column(name = "password_changed_at")
    private Instant passwordChangedAt;

    /**
     * The password was set by someone else (a chama official adding the member). Until the owner
     * chooses their own, the API answers AUTH_PASSWORD_CHANGE_REQUIRED to everything but that.
     */
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "user_roles",
            schema = "auth",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    private Set<Role> roles = new HashSet<>();

    /** Required by JPA. */
    protected User() {
    }

    public User(UUID tenantId, String email, String phoneNumber, String passwordHash, String firstName, String lastName) {
        this.id = UUID.randomUUID();
        this.tenantId = java.util.Objects.requireNonNull(tenantId, "tenantId");
        this.email = normalizeEmail(email);
        this.phoneNumber = PhoneNumber.of(phoneNumber).value();
        this.password = requireText(passwordHash, "password");
        this.firstName = requireText(firstName, "firstName");
        this.lastName = requireText(lastName, "lastName");
    }

    /**
     * Creates a new account in {@code tenantId} with its default role and records
     * {@link UserRegisteredEvent}.
     *
     * @param selfRegistered true when the person signed up themselves (they then apply for
     *                       membership); false for accounts created by the system, such as a
     *                       new chama's first administrator
     */
    public static User register(UUID tenantId, String email, String phoneNumber, String passwordHash,
                                String firstName, String lastName, Role defaultRole, boolean selfRegistered,
                                Instant now) {
        User user = new User(tenantId, email, phoneNumber, passwordHash, firstName, lastName);
        user.addRole(defaultRole);
        user.registerEvent(UserRegisteredEvent.of(tenantId, user.id, user.email, user.phoneNumber,
                user.firstName, user.lastName, selfRegistered, now));
        return user;
    }

    public static String normalizeEmail(String email) {
        if (email == null || email.isBlank() || !email.contains("@")) {
            throw new DomainValidationException("AUTH_INVALID_EMAIL", "A valid email address is required");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException("AUTH_INVALID_" + field.toUpperCase(Locale.ROOT), field + " is required");
        }
        return value.trim();
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

    /** A new address is unproven, so changing the email clears verification. */
    public void setEmail(String email) {
        String normalized = normalizeEmail(email);
        if (!normalized.equals(this.email)) {
            this.emailVerifiedAt = null;
        }
        this.email = normalized;
    }

    public boolean isEmailVerified() {
        return emailVerifiedAt != null;
    }

    public Instant getEmailVerifiedAt() {
        return emailVerifiedAt;
    }

    public void markEmailVerified(Instant now) {
        if (emailVerifiedAt == null) {
            emailVerifiedAt = now;
        }
    }

    public Instant getPasswordChangedAt() {
        return passwordChangedAt;
    }

    /** The only way to change the password, so {@link #passwordChangedAt} is always recorded. */
    public void changePassword(String newPasswordHash, Instant now) {
        this.password = requireText(newPasswordHash, "password");
        this.passwordChangedAt = now;
        this.mustChangePassword = false;
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    /** The current password is temporary: its owner must replace it at their next sign-in. */
    public void requirePasswordChange() {
        this.mustChangePassword = true;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = PhoneNumber.of(phoneNumber).value();
    }

    public String getPassword() {
        return password;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = requireText(firstName, "firstName");
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = requireText(lastName, "lastName");
    }

    public boolean isEnabled() {
        return isEnabled;
    }

    public void setEnabled(boolean enabled) {
        isEnabled = enabled;
    }

    /** Platform roles Spring Security puts in the access token. */
    public Set<Role> getRoles() {
        return roles;
    }

    public void setRoles(Set<Role> roles) {
        this.roles = roles;
    }

    /**
     * Adds a single role. Safer than {@code getRoles().add(role)} from outside because the
     * mutation stays inside the aggregate root (a DDD principle).
     */
    public void addRole(Role role) {
        this.roles.add(role);
    }
}
