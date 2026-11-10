package ke.chama.modules.member.api;

import ke.chama.shared.security.ChamaPermission;

import java.util.Set;
import java.util.UUID;

/**
 * What a member may do in their chama, for modules whose rule depends on the record (e.g. who may
 * review a reported payment depends on what was paid). Empty unless the membership is ACTIVE.
 */
public interface MemberPermissions {

    Set<ChamaPermission> permissionsOf(UUID chamaId, UUID userId);
}
