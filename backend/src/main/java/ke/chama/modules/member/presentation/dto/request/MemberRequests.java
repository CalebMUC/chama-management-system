package ke.chama.modules.member.presentation.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import ke.chama.modules.member.domain.model.ChamaRole;
import ke.chama.shared.security.ChamaPermission;

import java.util.Set;

public final class MemberRequests {

    private MemberRequests() {
    }

    public record InviteMemberRequest(@NotBlank @Email @Size(max = 255) String email) {
        public InviteMemberRequest {
            email = email == null ? null : email.trim();
        }
    }

    public record SuspendMemberRequest(@NotBlank @Size(max = 500) String reason) {
    }

    /** MEMBER is always kept even if omitted. */
    public record ChangeRolesRequest(@NotNull Set<ChamaRole> roles) {
    }

    public record RolePermissionsRequest(@NotNull Set<ChamaPermission> permissions) {
    }
}
