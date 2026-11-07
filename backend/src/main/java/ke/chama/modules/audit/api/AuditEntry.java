package ke.chama.modules.audit.api;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One accountable action.
 *
 * @param chamaId     the chama (tenant) it happened in. Platform actions on a chama, such as
 *                    licence changes, are recorded in that chama's log.
 * @param actorUserId who did it; null only for system jobs
 * @param action      stable UPPER_SNAKE name, e.g. {@code MEMBER_SUSPENDED}
 * @param targetType  what was acted on, e.g. {@code MEMBERSHIP}
 * @param targetId    its ID
 * @param details     extra facts, e.g. before/after values; stored as JSON. Never put secrets here.
 */
public record AuditEntry(UUID chamaId,
                         UUID actorUserId,
                         String action,
                         String targetType,
                         String targetId,
                         Map<String, ?> details) {

    public AuditEntry {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(targetType, "targetType");
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    public static AuditEntry of(UUID chamaId, UUID actorUserId, String action, String targetType, Object targetId) {
        return new AuditEntry(chamaId, actorUserId, action, targetType, targetId == null ? null : targetId.toString(), Map.of());
    }

    public AuditEntry with(Map<String, ?> moreDetails) {
        return new AuditEntry(chamaId, actorUserId, action, targetType, targetId, moreDetails);
    }
}
