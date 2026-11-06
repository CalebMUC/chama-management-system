package ke.chama.modules.member.application.service;

import ke.chama.modules.audit.api.AuditEntry;
import ke.chama.modules.audit.api.AuditTrail;
import ke.chama.modules.member.application.port.in.RolePermissionAdmin;
import ke.chama.modules.member.application.port.out.RolePermissionRepository;
import ke.chama.modules.member.domain.exception.MemberExceptions.PermissionLockoutException;
import ke.chama.modules.member.domain.model.ChamaRole;
import ke.chama.modules.member.domain.model.DefaultRolePermissions;
import ke.chama.shared.security.ChamaPermission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class RolePermissionService implements RolePermissionAdmin {

    private final RolePermissionRepository rolePermissions;
    private final AuditTrail auditTrail;

    public RolePermissionService(RolePermissionRepository rolePermissions, AuditTrail auditTrail) {
        this.rolePermissions = rolePermissions;
        this.auditTrail = auditTrail;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<ChamaRole, Set<ChamaPermission>> permissionsByRole(UUID chamaId) {
        return rolePermissions.findAll(chamaId);
    }

    @Override
    public Set<ChamaPermission> updateRolePermissions(UUID chamaId, UUID actorUserId, ChamaRole role,
                                                      Set<ChamaPermission> permissions) {
        Set<ChamaPermission> updated = permissions.isEmpty() ? EnumSet.noneOf(ChamaPermission.class) : EnumSet.copyOf(permissions);
        if (role == ChamaRole.CHAIRPERSON && !updated.containsAll(DefaultRolePermissions.LOCKED_FOR_CHAIRPERSON)) {
            throw new PermissionLockoutException();
        }

        Set<ChamaPermission> before = rolePermissions.findAll(chamaId).getOrDefault(role, Set.of());
        rolePermissions.replace(chamaId, role, updated);

        auditTrail.record(AuditEntry.of(chamaId, actorUserId, "ROLE_PERMISSIONS_CHANGED", "ROLE", role.name())
                .with(Map.of(
                        "granted", sortedNames(difference(updated, before)),
                        "revoked", sortedNames(difference(before, updated)))));
        return updated;
    }

    /** Called when a chama is founded. */
    void seedDefaults(UUID chamaId) {
        for (ChamaRole role : ChamaRole.values()) {
            rolePermissions.replace(chamaId, role, DefaultRolePermissions.forRole(role));
        }
    }

    private static Set<ChamaPermission> difference(Set<ChamaPermission> a, Set<ChamaPermission> b) {
        EnumSet<ChamaPermission> result = EnumSet.noneOf(ChamaPermission.class);
        result.addAll(a);
        result.removeAll(b);
        return result;
    }

    private static java.util.List<String> sortedNames(Set<ChamaPermission> permissions) {
        return permissions.stream().map(Enum::name).sorted().toList();
    }
}
