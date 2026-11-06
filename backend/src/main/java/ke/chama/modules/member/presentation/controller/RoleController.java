package ke.chama.modules.member.presentation.controller;

import ke.chama.shared.web.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import ke.chama.modules.member.application.port.in.RolePermissionAdmin;
import ke.chama.modules.member.domain.model.ChamaRole;
import ke.chama.modules.member.presentation.dto.request.MemberRequests.RolePermissionsRequest;
import ke.chama.shared.security.ChamaPermission;
import ke.chama.shared.security.CurrentUser;
import ke.chama.shared.security.RequiresChamaPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * <pre>
 * GET /api/v1/chamas/{chamaId}/roles                      MEMBER_VIEW              role -> permissions
 * PUT /api/v1/chamas/{chamaId}/roles/{role}/permissions   ROLE_PERMISSIONS_MANAGE  {permissions}
 * </pre>
 */
@Tag(name = "Roles & permissions", description = "Which permissions each role holds in a chama")
@RestController
@RequestMapping("/api/v1/chamas/{chamaId}/roles")
class RoleController {

    private final RolePermissionAdmin rolePermissions;

    RoleController(RolePermissionAdmin rolePermissions) {
        this.rolePermissions = rolePermissions;
    }

    @GetMapping
    @RequiresChamaPermission(ChamaPermission.MEMBER_VIEW)
    ApiResponse<Map<ChamaRole, Set<ChamaPermission>>> list(@PathVariable UUID chamaId) {
        return ApiResponse.ok(rolePermissions.permissionsByRole(chamaId));
    }

    @PutMapping("/{role}/permissions")
    @RequiresChamaPermission(ChamaPermission.ROLE_PERMISSIONS_MANAGE)
    ApiResponse<Set<ChamaPermission>> update(@PathVariable UUID chamaId, @PathVariable ChamaRole role,
                                             @Valid @RequestBody RolePermissionsRequest request) {
        return ApiResponse.ok(rolePermissions.updateRolePermissions(chamaId, CurrentUser.id(), role,
                request.permissions()), "Permissions updated");
    }
}
