package ke.chama.modules.memberexit.infrastructure.persistence;

import ke.chama.modules.memberexit.application.port.out.ExitRepository;
import ke.chama.modules.memberexit.domain.model.MemberExit;
import ke.chama.modules.memberexit.domain.model.Settlement;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Plain JDBC, like the other money modules. Row-level security restricts every statement to the current chama. */
@Repository
class JdbcExitRepository implements ExitRepository {

    private static final String COLUMNS = """
            id, tenant_id, member_user_id, reason, currency, requested_by, required_approvals, status, rejected_by,
            rejected_at, rejection_reason, cancelled_at, settled_on, settled_by, payment_method, payment_reference,
            settlement_entry_id, created_at, version""";

    private final NamedParameterJdbcTemplate jdbc;

    JdbcExitRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(MemberExit e) {
        Settlement s = e.settlement();
        jdbc.update("""
                insert into member_exit.exits (id, tenant_id, member_user_id, reason, currency, refundable_minor,
                                               forfeited_minor, loans_minor, penalties_minor, payout_minor, requested_by,
                                               required_approvals, status, created_at)
                values (:id, :tenant, :member, :reason, :currency, :refundable, :forfeited, :loans, :penalties, :payout,
                        :requestedBy, :requiredApprovals, :status, :createdAt)""",
                new MapSqlParameterSource()
                        .addValue("id", e.id())
                        .addValue("tenant", e.chamaId())
                        .addValue("member", e.memberUserId())
                        .addValue("reason", e.reason())
                        .addValue("currency", s.currency())
                        .addValue("refundable", s.refundableMinor())
                        .addValue("forfeited", s.forfeitedMinor())
                        .addValue("loans", s.loansMinor())
                        .addValue("penalties", s.penaltiesMinor())
                        .addValue("payout", s.payoutMinor())
                        .addValue("requestedBy", e.requestedBy())
                        .addValue("requiredApprovals", e.requiredApprovals())
                        .addValue("status", e.status().name())
                        .addValue("createdAt", Timestamp.from(e.createdAt())));
        int n = 0;
        for (Settlement.Line line : s.lines()) {
            jdbc.update("""
                    insert into member_exit.lines (tenant_id, exit_id, line_no, kind, source_id, account_code, description,
                                                   amount_minor)
                    values (:tenant, :exit, :n, :kind, :source, :account, :description, :amount)""",
                    new MapSqlParameterSource()
                            .addValue("tenant", e.chamaId())
                            .addValue("exit", e.id())
                            .addValue("n", ++n)
                            .addValue("kind", line.kind().name())
                            .addValue("source", line.sourceId())
                            .addValue("account", line.accountCode())
                            .addValue("description", line.description())
                            .addValue("amount", line.amountMinor()));
        }
        saveApprovals(e);
    }

    @Override
    public boolean update(MemberExit e) {
        int rows = jdbc.update("""
                update member_exit.exits
                   set status = :status, rejected_by = :rejectedBy, rejected_at = :rejectedAt, rejection_reason = :reason,
                       cancelled_at = :cancelledAt, settled_on = :settledOn, settled_by = :settledBy,
                       payment_method = :method, payment_reference = :reference, settlement_entry_id = :entry,
                       version = version + 1
                 where tenant_id = :tenant and id = :id and version = :version""",
                new MapSqlParameterSource()
                        .addValue("status", e.status().name())
                        .addValue("rejectedBy", e.rejectedBy())
                        .addValue("rejectedAt", timestamp(e.rejectedAt()))
                        .addValue("reason", e.rejectionReason())
                        .addValue("cancelledAt", timestamp(e.cancelledAt()))
                        .addValue("settledOn", e.settledOn() == null ? null : Date.valueOf(e.settledOn()))
                        .addValue("settledBy", e.settledBy())
                        .addValue("method", e.paymentMethod())
                        .addValue("reference", e.paymentReference())
                        .addValue("entry", e.settlementEntryId())
                        .addValue("tenant", e.chamaId())
                        .addValue("id", e.id())
                        .addValue("version", e.version()));
        if (rows != 1) {
            return false;
        }
        saveApprovals(e);
        return true;
    }

    private void saveApprovals(MemberExit e) {
        for (MemberExit.Approval a : e.approvals()) {
            jdbc.update("""
                    insert into member_exit.approvals (tenant_id, exit_id, approver_user_id, note, approved_at)
                    values (:tenant, :exit, :approver, :note, :approvedAt)
                    on conflict do nothing""",
                    new MapSqlParameterSource()
                            .addValue("tenant", e.chamaId())
                            .addValue("exit", e.id())
                            .addValue("approver", a.approverUserId())
                            .addValue("note", a.note())
                            .addValue("approvedAt", Timestamp.from(a.approvedAt())));
        }
    }

    @Override
    public Optional<MemberExit> exit(UUID chamaId, UUID exitId) {
        return hydrate(chamaId, jdbc.query("select " + COLUMNS + " from member_exit.exits where tenant_id = :tenant and id = :id",
                Map.of("tenant", chamaId, "id", exitId), (rs, i) -> row(rs))).stream().findFirst();
    }

    @Override
    public Page<MemberExit> exits(UUID chamaId, MemberExit.Status status, UUID memberUserId, Pageable pageable) {
        StringBuilder where = new StringBuilder(" where tenant_id = :tenant");
        MapSqlParameterSource params = new MapSqlParameterSource("tenant", chamaId);
        if (status != null) {
            where.append(" and status = :status");
            params.addValue("status", status.name());
        }
        if (memberUserId != null) {
            where.append(" and member_user_id = :member");
            params.addValue("member", memberUserId);
        }
        Long total = jdbc.queryForObject("select count(*) from member_exit.exits" + where, params, Long.class);
        params.addValue("limit", pageable.getPageSize()).addValue("offset", pageable.getOffset());
        List<Row> rows = jdbc.query("select " + COLUMNS + " from member_exit.exits" + where
                + " order by created_at desc limit :limit offset :offset", params, (rs, i) -> row(rs));
        return new PageImpl<>(hydrate(chamaId, rows), pageable, total == null ? 0 : total);
    }

    @Override
    public Optional<MemberExit> inProgress(UUID chamaId, UUID memberUserId) {
        return hydrate(chamaId, jdbc.query("select " + COLUMNS + " from member_exit.exits where tenant_id = :tenant"
                        + " and member_user_id = :member and status in ('PENDING_APPROVAL', 'APPROVED')",
                Map.of("tenant", chamaId, "member", memberUserId), (rs, i) -> row(rs))).stream().findFirst();
    }

    @Override
    public boolean referenceExists(UUID chamaId, String method, String reference) {
        Boolean exists = jdbc.queryForObject("""
                        select exists (select 1 from member_exit.exits where tenant_id = :tenant and payment_method = :method
                                          and upper(payment_reference) = upper(:reference))""",
                Map.of("tenant", chamaId, "method", method, "reference", reference), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    // ---- mapping --------------------------------------------------------------------------------

    /** An exits row before its lines and approvals are attached. */
    private record Row(UUID id, UUID chamaId, UUID member, String reason, String currency, UUID requestedBy,
                       int requiredApprovals, MemberExit.Status status, UUID rejectedBy, Instant rejectedAt,
                       String rejectionReason, Instant cancelledAt, LocalDate settledOn, UUID settledBy, String method,
                       String reference, UUID entry, Instant createdAt, long version) {
    }

    private static Row row(ResultSet rs) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("member_user_id", UUID.class), rs.getString("reason"), rs.getString("currency"),
                rs.getObject("requested_by", UUID.class), rs.getInt("required_approvals"),
                MemberExit.Status.valueOf(rs.getString("status")), rs.getObject("rejected_by", UUID.class),
                instant(rs, "rejected_at"), rs.getString("rejection_reason"), instant(rs, "cancelled_at"),
                rs.getDate("settled_on") == null ? null : rs.getDate("settled_on").toLocalDate(),
                rs.getObject("settled_by", UUID.class), rs.getString("payment_method"),
                rs.getString("payment_reference"), rs.getObject("settlement_entry_id", UUID.class),
                rs.getTimestamp("created_at").toInstant(), rs.getLong("version"));
    }

    /** Loads the lines and approvals of all the rows in two queries. */
    private List<MemberExit> hydrate(UUID chamaId, List<Row> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource("tenant", chamaId)
                .addValue("ids", rows.stream().map(Row::id).toList());
        Map<UUID, List<Settlement.Line>> lines = new HashMap<>();
        jdbc.query("""
                        select exit_id, kind, source_id, account_code, description, amount_minor from member_exit.lines
                         where tenant_id = :tenant and exit_id in (:ids) order by exit_id, line_no""", params,
                rs -> {
                    lines.computeIfAbsent(rs.getObject("exit_id", UUID.class), k -> new ArrayList<>())
                            .add(new Settlement.Line(Settlement.Kind.valueOf(rs.getString("kind")),
                                    rs.getObject("source_id", UUID.class), rs.getString("account_code"),
                                    rs.getString("description"), rs.getLong("amount_minor")));
                });
        Map<UUID, List<MemberExit.Approval>> approvals = new HashMap<>();
        jdbc.query("""
                        select exit_id, approver_user_id, note, approved_at from member_exit.approvals
                         where tenant_id = :tenant and exit_id in (:ids) order by approved_at""", params,
                rs -> {
                    approvals.computeIfAbsent(rs.getObject("exit_id", UUID.class), k -> new ArrayList<>())
                            .add(new MemberExit.Approval(rs.getObject("approver_user_id", UUID.class),
                                    rs.getString("note"), rs.getTimestamp("approved_at").toInstant()));
                });
        return rows.stream().map(r -> new MemberExit(r.id(), r.chamaId(), r.member(), r.reason(),
                new Settlement(r.currency(), lines.getOrDefault(r.id(), List.of())), r.requestedBy(),
                r.requiredApprovals(), approvals.getOrDefault(r.id(), List.of()), r.status(), r.rejectedBy(),
                r.rejectedAt(), r.rejectionReason(), r.cancelledAt(), r.settledOn(), r.settledBy(), r.method(),
                r.reference(), r.entry(), r.createdAt(), r.version())).toList();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
