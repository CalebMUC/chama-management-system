package ke.chama.modules.auth.domain.model;

import jakarta.persistence.*;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name="users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false,unique = true)
    private String email;

    @Column(name = "phone_number", nullable = false)
    private String phoneNumber;

    @Column(nullable = false)
    private String password;

    @Column(name = "first_name",nullable = false)
    private String firstName;

    @Column(name = "last_name",nullable = false)
    private String lastName;

    @Column(name = "is_enabled", nullable = false)
    private boolean isEnabled = true;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;


    @ManyToMany(fetch = FetchType.EAGER)

    @JoinTable
    (
            name = "user_roles",
            joinColumns = @JoinColumn(
                     name = "user_id"
    ),
            inverseJoinColumns = @JoinColumn(
                    name = "role_id"
               )
    )

    private Set<Role> roles = new HashSet<>();

//    No args constructor
    public User(){}

    public User(String email,String password,String firstName,String lastName){
        this.email = email;
        this.password = password;
        this.firstName = firstName;
        this.lastName = lastName;
    }

    // Java does not have auto=properties
    //getters and setters are declared manually or using Lombok

    public UUID getId() {return id; }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
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

    //Returns Full Role Collection used by Spring Security to build the User Detail object
    //to determine what the User Can Do


//Get a collection of Roles
    public Set<Role> getRoles() {
        return roles;
    }
    public void setRoles(Set<Role> roles ){
        this.roles = roles;
    }

    /**
     * Domain method — adds a single role to this user.
     * Calling this is safer than getRoles().add(role) from outside
     * the entity because it keeps the mutation logic inside the
     * aggregate root. This is a DDD principle.
     */
    public void addRole(Role role) { this.roles.add(role); }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
