package ke.chama.modules.member.infrastructure.persistence;

import ke.chama.modules.member.application.port.out.RolePermissionRepository;
import ke.chama.modules.member.domain.model.ChamaRole;
import ke.chama.shared.security.ChamaPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Plain JDBC for a simple (chama, role, permission) table and the one query that runs on
 * every protected request.
 */
@Repository
class JdbcRolePermissionRepository implements RolePermissionRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcRolePermissionRepository.class);

    private final JdbcTemplate jdbc;

    JdbcRolePermissionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void replace(UUID chamaId, ChamaRole role, Set<ChamaPermission> permissions) {
        jdbc.update("delete from member.role_permissions where chama_id = ? and role = ?", chamaId, role.name());
        List<Object[]> rows = permissions.stream()
                .map(p -> new Object[]{chamaId, role.name(), p.name()})
                .toList();
        if (!rows.isEmpty()) {
            jdbc.batchUpdate("insert into member.role_permissions (chama_id, role, permission) values (?, ?, ?)", rows);
        }
    }

    @Override
    public Map<ChamaRole, Set<ChamaPermission>> findAll(UUID chamaId) {
        Map<ChamaRole, Set<ChamaPermission>> result = new EnumMap<>(ChamaRole.class);
        for (ChamaRole role : ChamaRole.values()) {
            result.put(role, EnumSet.noneOf(ChamaPermission.class));
        }
        jdbc.query("select role, permission from member.role_permissions where chama_id = ?",
                rs -> {
                    Set<ChamaPermission> forRole = result.get(ChamaRole.valueOf(rs.getString("role")));
                    parse(rs.getString("permission")).ifPresent(forRole::add);
                },
                chamaId);
        return result;
    }

    /** Joins the caller's ACTIVE membership with the chama's role table. Indexed on both sides. */
    @Override
    public Set<ChamaPermission> effectivePermissions(UUID chamaId, UUID userId) {
        EnumSet<ChamaPermission> result = EnumSet.noneOf(ChamaPermission.class);
        jdbc.query("""
                        select distinct rp.permission
                          from member.memberships m
                          join member.membership_roles mr on mr.membership_id = m.id
                          join member.role_permissions rp on rp.chama_id = m.chama_id and rp.role = mr.role
                         where m.chama_id = ? and m.user_id = ? and m.status = 'ACTIVE'""",
                rs -> {
                    parse(rs.getString("permission")).ifPresent(result::add);
                },
                chamaId, userId);
        return result;
    }

    /** Ignores names no longer in the enum instead of failing every request for that chama. */
    private static java.util.Optional<ChamaPermission> parse(String name) {
        try {
            return java.util.Optional.of(ChamaPermission.valueOf(name));
        } catch (IllegalArgumentException e) {
            log.warn("Ignoring unknown permission '{}' in member.role_permissions", name);
            return java.util.Optional.empty();
        }
    }
}
