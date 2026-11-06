package ke.chama.shared.tenancy;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The chama (tenant) the current thread is working for. Every chama shares the database; each
 * tenant-owned row carries a {@code tenant_id}, and this context decides which rows are visible:
 * <ul>
 *     <li>Hibernate adds {@code tenant_id = current} to every query on {@code @TenantId} entities,
 *     and sets it on every insert ({@link TenantIdentifierResolver}).</li>
 *     <li>PostgreSQL row-level security rejects any other row, even for raw SQL
 *     ({@link TenantAwareDataSource} passes the tenant to the database session).</li>
 * </ul>
 * Set once per request by {@link TenantContextFilter}: from the access token for logged-in users,
 * from the {@value #HEADER} header for public endpoints (login, register, password reset), and
 * otherwise {@link #PLATFORM}.
 * <p>
 * Background work (scheduled jobs, platform administration) uses {@link #callAs} or
 * {@link #callAsSystem}. The tenant must be chosen <em>before</em> a transaction starts: the
 * database session and the Hibernate session both capture it when the transaction begins.
 */
public final class TenantContext {

    /** Request header that names the chama on public endpoints, e.g. {@code X-Chama-Code: mokica}. */
    public static final String HEADER = "X-Chama-Code";

    /** The platform operator's own tenant. Platform administrators belong here, not to a chama. */
    public static final UUID PLATFORM = new UUID(0L, 1L);

    /** Database marker for "no tenant": matches no row. */
    static final UUID NONE = new UUID(0L, 0L);

    /** Hibernate root marker for {@link #callAsSystem}: every tenant is visible. */
    static final UUID SYSTEM = new UUID(-1L, -1L);

    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    /**
     * @param id         the tenant, or null when none is set or the requested code is unknown
     * @param code       the chama code, when known
     * @param unresolved true when a chama code was sent that matches no chama
     * @param system     true inside {@link #callAsSystem}
     */
    record State(UUID id, String code, boolean unresolved, boolean system) {
    }

    private TenantContext() {
    }

    // ---- Reading ------------------------------------------------------------------------------

    /** The current tenant, if any. Empty in system mode. */
    public static Optional<UUID> id() {
        State state = CURRENT.get();
        return state == null || state.system() ? Optional.empty() : Optional.ofNullable(state.id());
    }

    /** The current chama code, if known. */
    public static Optional<String> code() {
        State state = CURRENT.get();
        return state == null ? Optional.empty() : Optional.ofNullable(state.code());
    }

    /**
     * The current tenant. Throws {@link UnknownTenantException} (404) when the request named a
     * chama code that does not exist, or {@link TenantRequiredException} (400) when there is none.
     */
    public static UUID requireId() {
        State state = CURRENT.get();
        if (state != null && state.unresolved()) {
            throw new UnknownTenantException(state.code());
        }
        return id().orElseThrow(TenantRequiredException::new);
    }

    /** The current tenant, which must be a chama (not the platform tenant). */
    public static UUID requireChama() {
        UUID id = requireId();
        if (PLATFORM.equals(id)) {
            throw new TenantRequiredException();
        }
        return id;
    }

    public static boolean isPlatform() {
        return id().map(PLATFORM::equals).orElse(false);
    }

    public static boolean isSystem() {
        State state = CURRENT.get();
        return state != null && state.system();
    }

    /** What the database session sees: the tenant id, or {@link #NONE}. */
    static UUID databaseTenant() {
        return id().orElse(NONE);
    }

    // ---- Scoping ------------------------------------------------------------------------------

    /** Runs {@code work} as the given tenant, then restores the previous context. */
    public static <T> T callAs(UUID tenantId, String code, Supplier<T> work) {
        Objects.requireNonNull(tenantId, "tenantId");
        return within(new State(tenantId, code, false, false), work);
    }

    public static void runAs(UUID tenantId, String code, Runnable work) {
        callAs(tenantId, code, () -> {
            work.run();
            return null;
        });
    }

    /**
     * Runs {@code work} across all tenants: row-level security is bypassed and Hibernate's tenant
     * filter is off. Only for platform administration and housekeeping jobs. Do not create
     * tenant-owned entities in system mode; switch to that tenant with {@link #callAs} instead.
     */
    public static <T> T callAsSystem(Supplier<T> work) {
        return within(new State(null, null, false, true), work);
    }

    public static void runAsSystem(Runnable work) {
        callAsSystem(() -> {
            work.run();
            return null;
        });
    }

    private static <T> T within(State state, Supplier<T> work) {
        State previous = CURRENT.get();
        if (!state.equals(previous) && TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("The tenant cannot change inside a transaction: the database and "
                    + "Hibernate sessions captured it when the transaction began. Choose the tenant first.");
        }
        CURRENT.set(state);
        try {
            return work.get();
        } finally {
            restore(previous);
        }
    }

    // ---- Used by the request filter -------------------------------------------------------------

    static void set(UUID tenantId, String code) {
        CURRENT.set(new State(tenantId, code, false, false));
    }

    static void setUnresolved(String code) {
        CURRENT.set(new State(null, code, true, false));
    }

    static void clear() {
        CURRENT.remove();
    }

    private static void restore(State previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
