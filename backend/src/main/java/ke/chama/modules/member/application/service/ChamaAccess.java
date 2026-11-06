package ke.chama.modules.member.application.service;

import ke.chama.modules.member.application.port.out.MembershipRepository;
import ke.chama.modules.member.application.port.out.RolePermissionRepository;
import ke.chama.modules.member.domain.model.MembershipStatus;
import ke.chama.shared.security.AuthenticatedUser;
import ke.chama.shared.security.ChamaPermission;
import ke.chama.shared.security.CurrentUser;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * The bean behind {@code @RequiresChamaPermission} and {@code @RequiresChamaMembership}
 * (referenced by name as {@code @chamaAccess} in their SpEL).
 * <p>
 * Permissions are read from the database on every check, never from the token, so a suspension
 * or role change takes effect on the very next request.
 */
@Component("chamaAccess")
@Transactional(readOnly = true)
public class ChamaAccess {

    private final RolePermissionRepository rolePermissions;
    private final MembershipRepository memberships;

    public ChamaAccess(RolePermissionRepository rolePermissions, MembershipRepository memberships) {
        this.rolePermissions = rolePermissions;
        this.memberships = memberships;
    }

    public boolean has(UUID chamaId, String permission) {
        Optional<AuthenticatedUser> user = CurrentUser.find();
        if (chamaId == null || user.isEmpty()) {
            return false;
        }
        return rolePermissions.effectivePermissions(chamaId, user.get().userId())
                .contains(ChamaPermission.valueOf(permission));
    }

    public boolean isActiveMember(UUID chamaId) {
        Optional<AuthenticatedUser> user = CurrentUser.find();
        if (chamaId == null || user.isEmpty()) {
            return false;
        }
        return memberships.findByChamaIdAndUserIdAndStatus(chamaId, user.get().userId(), MembershipStatus.ACTIVE)
                .isPresent();
    }
}
