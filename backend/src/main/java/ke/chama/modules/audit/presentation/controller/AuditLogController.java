package ke.chama.modules.audit.presentation.controller;

import ke.chama.shared.web.ApiResponse;
import java.util.List;
import io.swagger.v3.oas.annotations.tags.Tag;
import ke.chama.modules.audit.application.port.in.AuditLogQueries;
import ke.chama.modules.audit.application.port.in.AuditLogQueries.AuditLogEntry;
import ke.chama.shared.security.ChamaPermission;
import ke.chama.shared.security.RequiresChamaPermission;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** GET /api/v1/chamas/{chamaId}/audit-log?action=&page=&size=   (AUDIT_LOG_VIEW) */
@Tag(name = "Audit log", description = "Append-only record of who did what in a chama")
@RestController
class AuditLogController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AuditLogQueries auditLog;

    AuditLogController(AuditLogQueries auditLog) {
        this.auditLog = auditLog;
    }

    @GetMapping("/api/v1/chamas/{chamaId}/audit-log")
    @RequiresChamaPermission(ChamaPermission.AUDIT_LOG_VIEW)
    ApiResponse<List<AuditLogEntry>> list(@PathVariable UUID chamaId,
                                          @RequestParam(required = false) String action,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "50") int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
        return ApiResponse.page(auditLog.forChama(chamaId, action, pageable));
    }
}
