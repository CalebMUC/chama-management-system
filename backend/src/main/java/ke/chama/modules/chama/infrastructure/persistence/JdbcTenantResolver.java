package ke.chama.modules.chama.infrastructure.persistence;

import ke.chama.shared.tenancy.TenantContext;
import ke.chama.shared.tenancy.TenantResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Finds a chama by code for the request filter, before any tenant is known. Runs in system mode
 * because the lookup itself is what decides the tenant; it reads only the id.
 */
@Component
class JdbcTenantResolver implements TenantResolver {

    private final JdbcTemplate jdbc;

    JdbcTenantResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UUID> findIdByCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String normalized = code.trim().toLowerCase(Locale.ROOT);
        return TenantContext.callAsSystem(() -> jdbc.query("select id from chama.chamas where code = ?",
                (rs, i) -> rs.getObject("id", UUID.class), normalized).stream().findFirst());
    }
}
