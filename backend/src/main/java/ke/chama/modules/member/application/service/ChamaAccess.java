package ke.chama.modules.member.application.service;

import ke.chama.shared.tenancy.TenantContext;
import ke.chama.modules.member.api.MemberPermissions;
import ke.chama.modules.member.application.port.out.MembershipRepository;
import ke.chama.modules.member.application.port.out.RolePermissionRepository;
import ke.chama.modules.member.domain.model.MembershipStatus;
import ke.chama.shared.security.AuthenticatedUser;
import ke.chama.shared.security.ChamaPermission;
import ke.chama.shared.security.CurrentUser;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Set;
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
public class ChamaAccess implements MemberPermissions {

    private final RolePermissionRepository rolePermissions;
    private final MembershipRepository memberships;

    public ChamaAccess(RolePermissionRepository rolePermissions, MembershipRepository memberships) {
        this.rolePermissions = rolePermissions;
        this.memberships = memberships;
    }

    /** The caller holds {@code permission} in their own chama (the tenant of their token). */
    public boolean has(String permission) {
        Optional<AuthenticatedUser> user = CurrentUser.find();
        Optional<UUID> chamaId = TenantContext.id().filter(id -> !TenantContext.PLATFORM.equals(id));
        if (chamaId.isEmpty() || user.isEmpty()) {
            return false;
        }
        return rolePermissions.effectivePermissions(chamaId.get(), user.get().userId())
                .contains(ChamaPermission.valueOf(permission));
    }

    /** The caller holds at least one of {@code permissions}. */
    public boolean hasAny(String... permissions) {
        for (String permission : permissions) {
            if (has(permission)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Set<ChamaPermission> permissionsOf(UUID chamaId, UUID userId) {
        return rolePermissions.effectivePermissions(chamaId, userId);
    }

    public boolean isActiveMember() {
        Optional<AuthenticatedUser> user = CurrentUser.find();
        Optional<UUID> chamaId = TenantContext.id().filter(id -> !TenantContext.PLATFORM.equals(id));
        if (chamaId.isEmpty() || user.isEmpty()) {
            return false;
        }
        return memberships.findByChamaIdAndUserIdAndStatus(chamaId.get(), user.get().userId(), MembershipStatus.ACTIVE)
                .isPresent();
    }
}
