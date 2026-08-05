package ke.chama.auth.domain.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * User entity — maps to the "users" table in chama_auth database.
 *
 * .NET equivalent:
 *   public class User { } + modelBuilder.Entity<User>() config in DbContext
 *
 * This is the AGGREGATE ROOT of the auth bounded context.
 * All identity and access management flows through this class.
 */
@Entity   // Tells Hibernate: this class maps to a database table.
// .NET equivalent: [Table] attribute or just being in DbSet<User>
@Table(name = "users")  // Explicit table name. Without this Hibernate
// would default to "user" — a reserved word in
// PostgreSQL that causes errors. Always be explicit.
public class User {

    /**
     * PRIMARY KEY
     *
     * @Id        = marks this as the primary key column
     * @GeneratedValue(strategy = GenerationType.UUID)
     *            = tells Hibernate to auto-generate a UUID value on INSERT
     *
     * .NET equivalent:
     *   [Key]
     *   public Guid Id { get; set; } = Guid.NewGuid();
     *
     * Why UUID and not auto-increment Long?
     * - Works across distributed services without coordination
     * - No ID enumeration attacks (attacker can't guess /users/1, /users/2)
     * - Safe to generate client-side if needed
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID userId;

    /**
     * SIMPLE COLUMNS
     *
     * @Column maps the Java field to a database column.
     *
     * nullable = false  → adds NOT NULL constraint in the DB schema
     * unique = true     → adds a UNIQUE index on this column
     *
     * .NET equivalent in EF Core fluent API:
     *   builder.Property(u => u.Email)
     *          .IsRequired()
     *          .HasMaxLength(255);
     *   builder.HasIndex(u => u.Email).IsUnique();
     *
     * Note: when the Java field name matches the column name
     * (camelCase → snake_case conversion is automatic in Hibernate),
     * you can omit @Column entirely. But explicit is always better
     * in financial systems — no surprises.
     */
    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;  // Stores BCrypt hash, never plaintext

    /**
     * name = "first_name" overrides Hibernate's default naming.
     * Without it, Hibernate would map firstName → firstname (lowercase)
     * which differs from the snake_case first_name in our SQL migration.
     * Always match the exact column name from your Flyway migration.
     *
     * .NET equivalent:
     *   [Column("first_name")]
     *   public string FirstName { get; set; }
     */
    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    @Column(name = "is_enabled", nullable = false)
    private boolean isEnabled = true;  // Soft disable without deleting

    /**
     * AUDIT TIMESTAMPS
     *
     * @CreationTimestamp  → Hibernate sets this automatically on INSERT.
     *                       updatable = false ensures it never changes.
     * @UpdateTimestamp    → Hibernate updates this automatically on every UPDATE.
     *
     * .NET equivalent:
     *   // You'd implement this in DbContext.SaveChanges() override:
     *   if (entry.State == EntityState.Added)
     *       entity.CreatedAt = DateTime.UtcNow;
     *   if (entry.State == EntityState.Modified)
     *       entity.UpdatedAt = DateTime.UtcNow;
     *
     * Hibernate handles this automatically — no DbContext override needed.
     */
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * MANY-TO-MANY RELATIONSHIP — User ↔ Role
     *
     * Business rule: one user can have multiple roles (a user might be
     * both ROLE_TREASURER and ROLE_MEMBER). One role can belong to many
     * users (many users share ROLE_MEMBER).
     *
     * ─── How this maps to the database ───────────────────────────────
     *
     *   users table          user_roles table       roles table
     *   ┌──────────┐         ┌──────────┬─────────┐  ┌──────────┐
     *   │ id (PK)  │──────── │ user_id  │ role_id │  │ id (PK)  │
     *   │ email    │   1     │ (FK)     │ (FK)    │  │ name     │
     *   └──────────┘   │  N  └──────────┴─────────┘  └──────────┘
     *                  └──────────────────────────── N ──┘
     *
     * user_roles is the JOIN TABLE — it has no entity class of its own.
     * Hibernate manages it automatically based on the @JoinTable config.
     *
     * ─── .NET equivalent in EF Core ──────────────────────────────────
     *
     *   // In User.cs:
     *   public ICollection<Role> Roles { get; set; }
     *
     *   // In DbContext.OnModelCreating():
     *   builder.Entity<User>()
     *       .HasMany(u => u.Roles)
     *       .WithMany(r => r.Users)
     *       .UsingEntity<Dictionary<string, object>>(
     *           "user_roles",
     *           j => j.HasOne<Role>()
     *                 .WithMany()
     *                 .HasForeignKey("role_id"),
     *           j => j.HasOne<User>()
     *                 .WithMany()
     *                 .HasForeignKey("user_id")
     *       );
     *
     * In Java, ALL of that configuration moves here onto the field.
     * ─────────────────────────────────────────────────────────────────
     *
     * @ManyToMany
     *   Declares the cardinality. Hibernate knows to look for or create
     *   a join table. fetch = FetchType.EAGER means roles are loaded
     *   in the same query as the user (one JOIN). Use EAGER here because
     *   Spring Security needs roles immediately to build the UserDetails
     *   object — a lazy load would trigger outside the session and fail.
     *
     * @JoinTable(name = "user_roles")
     *   Names the join table. Without this Hibernate auto-generates
     *   a name like "user_role" which may not match your migration SQL.
     *
     * joinColumns = @JoinColumn(name = "user_id")
     *   Declares which column in user_roles points back to THIS entity
     *   (User). "This side" of the join table.
     *
     * inverseJoinColumns = @JoinColumn(name = "role_id")
     *   Declares which column in user_roles points to the OTHER entity
     *   (Role). "That side" of the join table.
     *
     * Set<Role> vs List<Role>:
     *   Set prevents duplicate roles being assigned to the same user.
     *   Hibernate also avoids a known bug with duplicate rows when
     *   using EAGER fetch + JOIN with List. Always use Set for
     *   @ManyToMany collections.
     *
     * HashSet<>() initialises the collection so addRole() never throws
     * a NullPointerException before the entity is persisted.
     */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "user_roles",              // matches your V1 migration SQL
            joinColumns = @JoinColumn(
                    name = "user_id"              // FK column pointing to users.id
            ),
            inverseJoinColumns = @JoinColumn(
                    name = "role_id"              // FK column pointing to roles.id
            )
    )
    private Set<Role> roles = new HashSet<>();

    // ── Constructors ─────────────────────────────────────────────────

    /**
     * No-arg constructor required by JPA/Hibernate.
     * Hibernate uses reflection to instantiate entities when loading
     * from the database — it needs a public or protected no-arg constructor.
     *
     * .NET equivalent: EF Core does the same but handles it silently.
     * In Java you must declare it explicitly if you also declare
     * a parameterised constructor.
     */
    public User() {}

    /**
     * Convenience constructor for creating new users in application code.
     * Does not include id (auto-generated), roles (added separately),
     * or timestamps (managed by Hibernate).
     */
    public User(String email, String password,
                String firstName, String lastName) {
        this.email = email;
        this.password = password;
        this.firstName = firstName;
        this.lastName = lastName;
    }

    // ── Getters and Setters ──────────────────────────────────────────
    //
    // Java does not have C# auto-properties (public string Email { get; set; })
    // You must write getters and setters manually, or use Lombok's
    // @Getter/@Setter annotations to generate them at compile time.
    //
    // We write them manually here for clarity while learning.
    // In production Java code you would add Lombok to pom.xml and
    // replace all of this with @Getter @Setter on the class.

    public UUID getId() { return id; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public boolean isEnabled() { return isEnabled; }
    public void setEnabled(boolean enabled) { isEnabled = enabled; }

    /**
     * Returns the full role collection.
     * Spring Security calls this when building the UserDetails object
     * to determine what the authenticated user is allowed to do.
     */
    public Set<Role> getRoles() { return roles; }
    public void setRoles(Set<Role> roles) { this.roles = roles; }

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