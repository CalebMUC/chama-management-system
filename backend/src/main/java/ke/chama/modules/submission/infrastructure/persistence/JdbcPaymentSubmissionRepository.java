package ke.chama.modules.submission.infrastructure.persistence;

import ke.chama.modules.submission.application.port.out.PaymentSubmissionRepository;
import ke.chama.modules.submission.domain.model.PaymentSubmission;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Plain JDBC, like the other money modules. Row-level security restricts every statement to the current chama. */
@Repository
class JdbcPaymentSubmissionRepository implements PaymentSubmissionRepository {

    private static final String COLUMNS = """
            id, tenant_id, member_user_id, kind, target_id, amount_minor, currency, method, reference, paid_on, notes,
            submitted_at, status, reviewed_by, reviewed_at, review_note, cancelled_at, version""";

    private final NamedParameterJdbcTemplate jdbc;

    JdbcPaymentSubmissionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(PaymentSubmission s) {
        jdbc.update("""
                insert into submission.payment_submissions (id, tenant_id, member_user_id, kind, target_id, amount_minor,
                                                            currency, method, reference, paid_on, notes, submitted_at,
                                                            status)
                values (:id, :tenant, :member, :kind, :target, :amount, :currency, :method, :reference, :paidOn, :notes,
                        :submittedAt, :status)""",
                new MapSqlParameterSource()
                        .addValue("id", s.id())
                        .addValue("tenant", s.chamaId())
                        .addValue("member", s.memberUserId())
                        .addValue("kind", s.kind().name())
                        .addValue("target", s.targetId())
                        .addValue("amount", s.amountMinor())
                        .addValue("currency", s.currency())
                        .addValue("method", s.method())
                        .addValue("reference", s.reference())
                        .addValue("paidOn", Date.valueOf(s.paidOn()))
                        .addValue("notes", s.notes())
                        .addValue("submittedAt", Timestamp.from(s.submittedAt()))
                        .addValue("status", s.status().name()));
    }

    @Override
    public boolean update(PaymentSubmission s) {
        return jdbc.update("""
                update submission.payment_submissions
                   set status = :status, reviewed_by = :reviewedBy, reviewed_at = :reviewedAt, review_note = :note,
                       cancelled_at = :cancelledAt, version = version + 1
                 where tenant_id = :tenant and id = :id and version = :version""",
                new MapSqlParameterSource()
                        .addValue("status", s.status().name())
                        .addValue("reviewedBy", s.reviewedBy())
                        .addValue("reviewedAt", timestamp(s.reviewedAt()))
                        .addValue("note", s.reviewNote())
                        .addValue("cancelledAt", timestamp(s.cancelledAt()))
                        .addValue("tenant", s.chamaId())
                        .addValue("id", s.id())
                        .addValue("version", s.version())) == 1;
    }

    @Override
    public Optional<PaymentSubmission> submission(UUID chamaId, UUID submissionId) {
        return jdbc.query("select " + COLUMNS + " from submission.payment_submissions where tenant_id = :tenant and id = :id",
                Map.of("tenant", chamaId, "id", submissionId), (rs, i) -> row(rs)).stream().findFirst();
    }

    @Override
    public Page<PaymentSubmission> submissions(UUID chamaId, Collection<PaymentSubmission.Kind> kinds,
                                               PaymentSubmission.Status status, UUID memberUserId, Pageable pageable) {
        if (kinds.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, 0);
        }
        StringBuilder where = new StringBuilder(" where tenant_id = :tenant and kind in (:kinds)");
        MapSqlParameterSource params = new MapSqlParameterSource("tenant", chamaId)
                .addValue("kinds", kinds.stream().map(Enum::name).toList());
        if (status != null) {
            where.append(" and status = :status");
            params.addValue("status", status.name());
        }
        if (memberUserId != null) {
            where.append(" and member_user_id = :member");
            params.addValue("member", memberUserId);
        }
        Long total = jdbc.queryForObject("select count(*) from submission.payment_submissions" + where, params, Long.class);
        params.addValue("limit", pageable.getPageSize()).addValue("offset", pageable.getOffset());
        // The text block's leading whitespace is stripped, so the space before "order by" must be explicit.
        List<PaymentSubmission> rows = jdbc.query("select " + COLUMNS + " from submission.payment_submissions" + where + " " + """
                 order by case when status = 'PENDING' then 0 else 1 end,
                          case when status = 'PENDING' then submitted_at end asc,
                          coalesce(reviewed_at, cancelled_at, submitted_at) desc
                 limit :limit offset :offset""", params, (rs, i) -> row(rs));
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    @Override
    public boolean referenceTaken(UUID chamaId, String method, String reference) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                        select exists (select 1 from submission.payment_submissions
                                        where tenant_id = :tenant and method = :method and upper(reference) = upper(:reference)
                                          and status in ('PENDING', 'APPROVED'))""",
                Map.of("tenant", chamaId, "method", method, "reference", reference), Boolean.class));
    }

    @Override
    public Map<PaymentSubmission.Kind, Long> pendingByKind(UUID chamaId) {
        Map<PaymentSubmission.Kind, Long> counts = new EnumMap<>(PaymentSubmission.Kind.class);
        jdbc.query("""
                        select kind, count(*) as n from submission.payment_submissions
                         where tenant_id = :tenant and status = 'PENDING' group by kind""",
                Map.of("tenant", chamaId),
                rs -> {
                    counts.put(PaymentSubmission.Kind.valueOf(rs.getString("kind")), rs.getLong("n"));
                });
        return counts;
    }

    @Override
    public long pendingOf(UUID chamaId, UUID memberUserId) {
        Long n = jdbc.queryForObject("""
                        select count(*) from submission.payment_submissions
                         where tenant_id = :tenant and member_user_id = :member and status = 'PENDING'""",
                Map.of("tenant", chamaId, "member", memberUserId), Long.class);
        return n == null ? 0 : n;
    }

    private static PaymentSubmission row(ResultSet rs) throws SQLException {
        return new PaymentSubmission(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("member_user_id", UUID.class),
                PaymentSubmission.Kind.valueOf(rs.getString("kind")),
                rs.getObject("target_id", UUID.class),
                rs.getLong("amount_minor"),
                rs.getString("currency"),
                rs.getString("method"),
                rs.getString("reference"),
                rs.getDate("paid_on").toLocalDate(),
                rs.getString("notes"),
                instant(rs.getTimestamp("submitted_at")),
                PaymentSubmission.Status.valueOf(rs.getString("status")),
                rs.getObject("reviewed_by", UUID.class),
                instant(rs.getTimestamp("reviewed_at")),
                rs.getString("review_note"),
                instant(rs.getTimestamp("cancelled_at")),
                rs.getLong("version"));
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static Timestamp timestamp(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
