package ke.chama.modules.penalty.infrastructure.persistence;

import ke.chama.modules.penalty.api.PenaltyReports;
import ke.chama.modules.penalty.application.port.out.PenaltyRepository;
import ke.chama.modules.penalty.domain.model.Penalty;
import ke.chama.modules.penalty.domain.model.PenaltyPayment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Plain JDBC, like the other money modules. Row-level security restricts every statement to the current chama. */
@Repository
class JdbcPenaltyRepository implements PenaltyRepository {

    private static final String COLUMNS = """
            id, tenant_id, member_user_id, kind, source_key, description, amount_minor, currency, assessed_on,
            assessed_by, assessment_entry_id, paid_minor, waived_minor, status, waived_by, waived_at, waiver_reason,
            waiver_entry_id, created_at, version""";

    private final NamedParameterJdbcTemplate jdbc;

    JdbcPenaltyRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Penalty p) {
        jdbc.update("""
                insert into penalty.penalties (id, tenant_id, member_user_id, kind, source_key, description, amount_minor,
                                               currency, assessed_on, assessed_by, assessment_entry_id, paid_minor,
                                               waived_minor, status, created_at)
                values (:id, :tenant, :member, :kind, :source, :description, :amount, :currency, :assessedOn, :assessedBy,
                        :entry, 0, 0, :status, :createdAt)""",
                new MapSqlParameterSource()
                        .addValue("id", p.id())
                        .addValue("tenant", p.chamaId())
                        .addValue("member", p.memberUserId())
                        .addValue("kind", p.kind().name())
                        .addValue("source", p.sourceKey())
                        .addValue("description", p.description())
                        .addValue("amount", p.amountMinor())
                        .addValue("currency", p.currency())
                        .addValue("assessedOn", Date.valueOf(p.assessedOn()))
                        .addValue("assessedBy", p.assessedBy())
                        .addValue("entry", p.assessmentEntryId())
                        .addValue("status", p.status().name())
                        .addValue("createdAt", Timestamp.from(p.createdAt())));
    }

    @Override
    public boolean update(Penalty p) {
        return jdbc.update("""
                update penalty.penalties
                   set paid_minor = :paid, waived_minor = :waived, status = :status, waived_by = :waivedBy,
                       waived_at = :waivedAt, waiver_reason = :reason, waiver_entry_id = :waiverEntry, version = version + 1
                 where tenant_id = :tenant and id = :id and version = :version""",
                new MapSqlParameterSource()
                        .addValue("paid", p.paidMinor())
                        .addValue("waived", p.waivedMinor())
                        .addValue("status", p.status().name())
                        .addValue("waivedBy", p.waivedBy())
                        .addValue("waivedAt", p.waivedAt() == null ? null : Timestamp.from(p.waivedAt()))
                        .addValue("reason", p.waiverReason())
                        .addValue("waiverEntry", p.waiverEntryId())
                        .addValue("tenant", p.chamaId())
                        .addValue("id", p.id())
                        .addValue("version", p.version())) == 1;
    }

    @Override
    public Optional<Penalty> penalty(UUID chamaId, UUID penaltyId) {
        return jdbc.query("select " + COLUMNS + " from penalty.penalties where tenant_id = :tenant and id = :id",
                Map.of("tenant", chamaId, "id", penaltyId), PENALTY).stream().findFirst();
    }

    @Override
    public Page<Penalty> penalties(UUID chamaId, UUID memberUserId, Penalty.Status status, Pageable pageable) {
        StringBuilder where = new StringBuilder(" where tenant_id = :tenant");
        MapSqlParameterSource params = new MapSqlParameterSource("tenant", chamaId);
        if (memberUserId != null) {
            where.append(" and member_user_id = :member");
            params.addValue("member", memberUserId);
        }
        if (status != null) {
            where.append(" and status = :status");
            params.addValue("status", status.name());
        }
        Long total = jdbc.queryForObject("select count(*) from penalty.penalties" + where, params, Long.class);
        params.addValue("limit", pageable.getPageSize()).addValue("offset", pageable.getOffset());
        List<Penalty> rows = jdbc.query("select " + COLUMNS + " from penalty.penalties" + where
                + " order by assessed_on desc, created_at desc limit :limit offset :offset", params, PENALTY);
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    @Override
    public List<Penalty> penaltiesOf(UUID chamaId, UUID memberUserId) {
        return jdbc.query("select " + COLUMNS + " from penalty.penalties where tenant_id = :tenant and member_user_id = :member"
                + " order by assessed_on desc, created_at desc", Map.of("tenant", chamaId, "member", memberUserId), PENALTY);
    }

    @Override
    public Set<String> assessedSourceKeys(UUID chamaId) {
        return new HashSet<>(jdbc.query("""
                        select member_user_id, source_key from penalty.penalties
                         where tenant_id = :tenant and source_key is not null""",
                Map.of("tenant", chamaId),
                (rs, i) -> rs.getObject("member_user_id", UUID.class) + "/" + rs.getString("source_key")));
    }

    @Override
    public void insertPayment(PenaltyPayment p) {
        jdbc.update("""
                insert into penalty.payments (id, tenant_id, penalty_id, member_user_id, amount_minor, currency, method,
                                              reference, paid_on, ledger_entry_id, recorded_by, recorded_at)
                values (:id, :tenant, :penalty, :member, :amount, :currency, :method, :reference, :paidOn, :entry,
                        :recordedBy, :recordedAt)""",
                new MapSqlParameterSource()
                        .addValue("id", p.id())
                        .addValue("tenant", p.chamaId())
                        .addValue("penalty", p.penaltyId())
                        .addValue("member", p.memberUserId())
                        .addValue("amount", p.amountMinor())
                        .addValue("currency", p.currency())
                        .addValue("method", p.method())
                        .addValue("reference", p.reference())
                        .addValue("paidOn", Date.valueOf(p.paidOn()))
                        .addValue("entry", p.ledgerEntryId())
                        .addValue("recordedBy", p.recordedBy())
                        .addValue("recordedAt", Timestamp.from(p.recordedAt())));
    }

    @Override
    public List<PenaltyPayment> payments(UUID chamaId, UUID penaltyId) {
        return jdbc.query("""
                        select id, tenant_id, penalty_id, member_user_id, amount_minor, currency, method, reference, paid_on,
                               ledger_entry_id, recorded_by, recorded_at
                          from penalty.payments where tenant_id = :tenant and penalty_id = :penalty order by recorded_at""",
                Map.of("tenant", chamaId, "penalty", penaltyId),
                (rs, i) -> new PenaltyPayment(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                        rs.getObject("penalty_id", UUID.class), rs.getObject("member_user_id", UUID.class),
                        rs.getLong("amount_minor"), rs.getString("currency"), rs.getString("method"),
                        rs.getString("reference"), rs.getDate("paid_on").toLocalDate(),
                        rs.getObject("ledger_entry_id", UUID.class), rs.getObject("recorded_by", UUID.class),
                        rs.getTimestamp("recorded_at").toInstant()));
    }

    @Override
    public boolean referenceExists(UUID chamaId, String method, String reference) {
        Boolean exists = jdbc.queryForObject("""
                        select exists (select 1 from penalty.payments
                                        where tenant_id = :tenant and method = :method and upper(reference) = upper(:reference))""",
                Map.of("tenant", chamaId, "method", method, "reference", reference), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public PenaltyReports.PenaltySummary summary(UUID chamaId, LocalDate from, LocalDate to, String zone, int topOwing) {
        MapSqlParameterSource params = new MapSqlParameterSource("tenant", chamaId)
                .addValue("from", Date.valueOf(from)).addValue("to", Date.valueOf(to)).addValue("zone", zone)
                .addValue("top", topOwing);
        return jdbc.queryForObject("""
                        select
                          coalesce(sum(amount_minor - paid_minor - waived_minor) filter (where status = 'OUTSTANDING'), 0) as owed,
                          count(*) filter (where status = 'OUTSTANDING') as owed_count,
                          count(distinct member_user_id) filter (where status = 'OUTSTANDING') as members_owing,
                          coalesce(sum(amount_minor) filter (where assessed_on between :from and :to), 0) as assessed,
                          count(*) filter (where assessed_on between :from and :to) as assessed_count,
                          coalesce(sum(amount_minor) filter (where assessed_on between :from and :to
                                                               and kind = 'LATE_CONTRIBUTION'), 0) as late,
                          coalesce(sum(amount_minor) filter (where assessed_on between :from and :to and kind = 'FINE'), 0) as fines,
                          coalesce(sum(waived_minor) filter (where waived_at is not null
                                   and cast(waived_at at time zone :zone as date) between :from and :to), 0) as waived,
                          (select coalesce(sum(pp.amount_minor), 0) from penalty.payments pp
                            where pp.tenant_id = :tenant and pp.paid_on between :from and :to) as collected
                          from penalty.penalties
                         where tenant_id = :tenant""",
                params,
                (rs, i) -> new PenaltyReports.PenaltySummary(rs.getLong("owed"), rs.getInt("owed_count"),
                        rs.getInt("members_owing"), rs.getLong("assessed"), rs.getInt("assessed_count"), rs.getLong("late"),
                        rs.getLong("fines"), rs.getLong("collected"), rs.getLong("waived"),
                        topOwing == 0 ? List.of() : jdbc.query("""
                                        select member_user_id, sum(amount_minor - paid_minor - waived_minor) as owed,
                                               count(*) as n
                                          from penalty.penalties
                                         where tenant_id = :tenant and status = 'OUTSTANDING'
                                         group by member_user_id
                                         order by owed desc, member_user_id
                                         limit :top""",
                                params,
                                (r, j) -> new PenaltyReports.MemberOwing(r.getObject("member_user_id", UUID.class),
                                        r.getLong("owed"), r.getInt("n")))));
    }

    private static final RowMapper<Penalty> PENALTY = (rs, i) -> new Penalty(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("member_user_id", UUID.class),
            Penalty.Kind.valueOf(rs.getString("kind")),
            rs.getString("source_key"),
            rs.getString("description"),
            rs.getLong("amount_minor"),
            rs.getString("currency"),
            rs.getDate("assessed_on").toLocalDate(),
            rs.getObject("assessed_by", UUID.class),
            rs.getObject("assessment_entry_id", UUID.class),
            rs.getLong("paid_minor"),
            rs.getLong("waived_minor"),
            Penalty.Status.valueOf(rs.getString("status")),
            rs.getObject("waived_by", UUID.class),
            rs.getTimestamp("waived_at") == null ? null : rs.getTimestamp("waived_at").toInstant(),
            rs.getString("waiver_reason"),
            rs.getObject("waiver_entry_id", UUID.class),
            rs.getTimestamp("created_at").toInstant(),
            rs.getLong("version"));

    // ---- history brought over from spreadsheets -------------------------------------------------

    @Override
    public void markImported(UUID chamaId, java.util.Collection<UUID> penaltyIds, java.util.Collection<UUID> paymentIds,
                             UUID batchId) {
        if (!penaltyIds.isEmpty()) {
            jdbc.update("update penalty.penalties set import_batch_id = :batch where tenant_id = :tenant and id in (:ids)",
                    new MapSqlParameterSource().addValue("batch", batchId).addValue("tenant", chamaId)
                            .addValue("ids", penaltyIds));
        }
        if (!paymentIds.isEmpty()) {
            jdbc.update("update penalty.payments set import_batch_id = :batch where tenant_id = :tenant and id in (:ids)",
                    new MapSqlParameterSource().addValue("batch", batchId).addValue("tenant", chamaId)
                            .addValue("ids", paymentIds));
        }
    }

    @Override
    public List<Penalty> importedPenalties(UUID chamaId, UUID batchId) {
        return jdbc.query("select " + COLUMNS + " from penalty.penalties where tenant_id = :tenant"
                + " and import_batch_id = :batch order by assessed_on", Map.of("tenant", chamaId, "batch", batchId), PENALTY);
    }

    @Override
    public long laterPayments(UUID chamaId, UUID batchId) {
        Long count = jdbc.queryForObject("""
                        select count(*) from penalty.payments p
                          join penalty.penalties x on x.tenant_id = p.tenant_id and x.id = p.penalty_id
                         where p.tenant_id = :tenant and x.import_batch_id = :batch
                           and (p.import_batch_id is null or p.import_batch_id <> :batch)""",
                Map.of("tenant", chamaId, "batch", batchId), Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public void deleteImported(UUID chamaId, UUID batchId) {
        Map<String, Object> params = Map.of("tenant", chamaId, "batch", batchId);
        jdbc.update("""
                delete from penalty.payments p using penalty.penalties x
                 where p.tenant_id = :tenant and x.tenant_id = :tenant and p.penalty_id = x.id
                   and x.import_batch_id = :batch""", params);
        jdbc.update("delete from penalty.penalties where tenant_id = :tenant and import_batch_id = :batch", params);
    }

    @Override
    public Set<String> usedReferences(UUID chamaId) {
        return new HashSet<>(jdbc.queryForList("""
                        select method || ':' || upper(reference) from penalty.payments
                         where tenant_id = :tenant and reference is not null""",
                Map.of("tenant", chamaId), String.class));
    }
}
