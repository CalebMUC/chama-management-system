package ke.chama.modules.member.application.port.out;

import ke.chama.modules.member.domain.model.ChamaRole;
import ke.chama.shared.security.ChamaPermission;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Per-chama mapping of roles to permissions. */
public interface RolePermissionRepository {

    void replace(UUID chamaId, ChamaRole role, Set<ChamaPermission> permissions);

    Map<ChamaRole, Set<ChamaPermission>> findAll(UUID chamaId);

    /** Union of the permissions of every role the user holds; empty unless their membership is ACTIVE. */
    Set<ChamaPermission> effectivePermissions(UUID chamaId, UUID userId);
}
