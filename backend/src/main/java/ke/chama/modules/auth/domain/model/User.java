package ke.chama.modules.auth.domain.model;

import jakarta.persistence.*;
import ke.chama.shared.domain.AggregateRoot;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A login identity. Not the same thing as a chama member.
 * <p>
 * One user can belong to several chamas with a different role in each (Treasurer in one,
 * Member in another). Chama roles therefore live on the Membership in the member module,
 * not here. {@link #roles} is for platform-level roles only (e.g. ROLE_ADMIN); it is narrowed
 * to that in Phase 2.
 */
@Entity
@Table(name = "users", schema = "auth")
public class User extends AggregateRoot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    /** E.164 format (+2547XXXXXXXX). Needed for M-Pesa and SMS notifications. */
    @Column(name = "phone_number", nullable = false)
    private String phoneNumber;

    /** BCrypt hash. Never the raw password. */
    @Column(nullable = false)
    private String password;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    @Column(name = "is_enabled", nullable = false)
    private boolean isEnabled = true;

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

    public User(String email, String phoneNumber, String password, String firstName, String lastName) {
        this.email = email;
        this.phoneNumber = phoneNumber;
        this.password = password;
        this.firstName = firstName;
        this.lastName = lastName;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public boolean isEnabled() {
        return isEnabled;
    }

    public void setEnabled(boolean enabled) {
        isEnabled = enabled;
    }

    /** Roles Spring Security uses to build the authenticated principal. */
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
