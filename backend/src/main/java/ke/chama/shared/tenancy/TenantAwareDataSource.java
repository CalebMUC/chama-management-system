package ke.chama.shared.tenancy;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Hands the current tenant to PostgreSQL on every connection checkout, so row-level security
 * can enforce it:
 * <ul>
 *     <li>{@code role}: switches the session to the restricted application role. Superusers and
 *     table owners bypass row-level security, so the application must not run as either.</li>
 *     <li>{@code app.tenant_id}: the tenant the policies compare {@code tenant_id} with.</li>
 *     <li>{@code app.bypass_rls}: {@code on} only inside {@link TenantContext#callAsSystem}.</li>
 * </ul>
 * Settings are applied on every checkout, never left over from a previous borrower of the pooled
 * connection. With no tenant set the session sees no tenant rows at all: it fails closed.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    private static final String APPLY_SQL =
            "select set_config('role', ?, false), set_config('app.tenant_id', ?, false), "
                    + "set_config('app.bypass_rls', ?, false)";

    private final String databaseRole;

    /** @param databaseRole the role to switch to, e.g. {@code chama_app} */
    public TenantAwareDataSource(DataSource target, String databaseRole) {
        super(target);
        this.databaseRole = databaseRole;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return apply(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return apply(super.getConnection(username, password));
    }

    private Connection apply(Connection connection) throws SQLException {
        UUID tenant = TenantContext.databaseTenant();
        try (PreparedStatement statement = connection.prepareStatement(APPLY_SQL)) {
            statement.setString(1, databaseRole);
            statement.setString(2, TenantContext.NONE.equals(tenant) ? "" : tenant.toString());
            statement.setString(3, TenantContext.isSystem() ? "on" : "off");
            statement.execute();
            return connection;
        } catch (SQLException | RuntimeException e) {
            connection.close();
            throw e;
        }
    }
}
