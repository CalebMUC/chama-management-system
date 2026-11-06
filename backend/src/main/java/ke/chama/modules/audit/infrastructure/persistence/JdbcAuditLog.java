package ke.chama.modules.audit.infrastructure.persistence;

import jakarta.servlet.http.HttpServletRequest;
import ke.chama.modules.audit.api.AuditEntry;
import ke.chama.modules.audit.api.AuditTrail;
import ke.chama.modules.audit.application.port.in.AuditLogQueries;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Plain JDBC: the log is insert-and-read only, and {@code details} is JSONB, which is simpler to
 * handle as SQL than as a JPA mapping.
 */
@Repository
class JdbcAuditLog implements AuditTrail, AuditLogQueries {

    private static final TypeReference<Map<String, Object>> DETAILS = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final Clock clock;

    JdbcAuditLog(JdbcTemplate jdbc, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    /** MANDATORY: an audit entry must be part of the business transaction it describes. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditEntry entry) {
        jdbc.update("""
                        insert into audit.audit_log
                            (id, chama_id, actor_user_id, action, target_type, target_id, details, ip_address, occurred_at)
                        values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)""",
                UUID.randomUUID(), entry.chamaId(), entry.actorUserId(), entry.action(), entry.targetType(),
                entry.targetId(), json.writeValueAsString(entry.details()), currentClientIp(),
                Timestamp.from(clock.instant()));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AuditLogEntry> forChama(UUID chamaId, String action, Pageable pageable) {
        StringBuilder where = new StringBuilder(" where chama_id = ?");
        List<Object> args = new ArrayList<>(List.of(chamaId));
        if (action != null && !action.isBlank()) {
            where.append(" and action = ?");
            args.add(action);
        }

        Long total = jdbc.queryForObject("select count(*) from audit.audit_log" + where, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageable.getPageSize());
        pageArgs.add(pageable.getOffset());
        List<AuditLogEntry> rows = jdbc.query("""
                        select id, occurred_at, actor_user_id, action, target_type, target_id, details::text as details, ip_address
                          from audit.audit_log""" + where + " order by occurred_at desc, id desc limit ? offset ?",
                (rs, i) -> new AuditLogEntry(
                        rs.getObject("id", UUID.class),
                        rs.getTimestamp("occurred_at").toInstant(),
                        rs.getObject("actor_user_id", UUID.class),
                        rs.getString("action"),
                        rs.getString("target_type"),
                        rs.getString("target_id"),
                        json.readValue(rs.getString("details"), DETAILS),
                        rs.getString("ip_address")),
                pageArgs.toArray());

        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    private static String currentClientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            return request.getRemoteAddr();
        }
        return null;
    }
}
