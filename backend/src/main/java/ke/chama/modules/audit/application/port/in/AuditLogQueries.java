package ke.chama.modules.audit.application.port.in;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public interface AuditLogQueries {

    /** Newest first. {@code action} is an optional exact-match filter. */
    Page<AuditLogEntry> forChama(UUID chamaId, String action, Pageable pageable);

    record AuditLogEntry(UUID id,
                         Instant occurredAt,
                         UUID actorUserId,
                         String action,
                         String targetType,
                         String targetId,
                         Map<String, Object> details,
                         String ipAddress) {
    }
}
