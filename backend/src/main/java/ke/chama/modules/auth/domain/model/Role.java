package ke.chama.modules.auth.domain.model;


import jakarta.persistence.*;

import java.util.UUID;

/**
 * Platform-wide role. Chama roles (Treasurer, Secretary, Chairperson) are per membership
 * and live in the member module.
 */
@Entity
@Table(name = "roles", schema = "auth")
public class Role {

    /** Every registered user. */
    public static final String USER = "ROLE_USER";
    /** System administrator (platform operations, not chama business). */
    public static final String ADMIN = "ROLE_ADMIN";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID roleId;

    @Column(nullable=false,unique = true)
    private String name;

    public Role(){}

    public UUID getRoleId() {
        return roleId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
