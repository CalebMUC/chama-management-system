package ke.chama.modules.member.application.port.in;

import ke.chama.modules.member.domain.model.ChamaRole;
import ke.chama.shared.security.ChamaPermission;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Granular, per-chama control over what each role may do. */
public interface RolePermissionAdmin {

    Map<ChamaRole, Set<ChamaPermission>> permissionsByRole(UUID chamaId);

    /**
     * Replaces the role's permissions, effective immediately for everyone holding it. The
     * chairperson role cannot lose the permissions that manage roles; that would leave the chama
     * unmanageable.
     */
    Set<ChamaPermission> updateRolePermissions(UUID chamaId, UUID actorUserId, ChamaRole role,
                                               Set<ChamaPermission> permissions);
}
