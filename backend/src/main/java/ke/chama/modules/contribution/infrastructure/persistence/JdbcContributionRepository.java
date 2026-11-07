package ke.chama.modules.contribution.infrastructure.persistence;

import ke.chama.modules.contribution.application.port.out.ContributionRepository;
import ke.chama.modules.contribution.domain.model.ContributionPayment;
import ke.chama.modules.contribution.domain.model.ContributionPeriod;
import ke.chama.modules.contribution.domain.model.PaymentAllocation.Allocation;
import ke.chama.modules.contribution.domain.model.PaymentMethod;
import ke.chama.modules.contribution.domain.model.PeriodSchedule.Frequency;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Plain JDBC, like the ledger. Row-level security restricts every statement to the current chama. */
@Repository
class JdbcContributionRepository implements ContributionRepository {

    private static final String PERIOD_COLUMNS =
            "id, tenant_id, period_key, frequency, start_date, end_date, due_date, amount_due_minor, currency, opened_at";

    private static final String PAYMENT_COLUMNS = """
            id, tenant_id, member_user_id, amount_minor, currency, method, reference, paid_on, notes, status,
            ledger_entry_id, recorded_by, recorded_at, reversed_by, reversed_at, reversal_reason, reversal_entry_id,
            version""";

    private final NamedParameterJdbcTemplate jdbc;

    JdbcContributionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- periods and dues ---------------------------------------------------------------------

    @Override
    public Optional<ContributionPeriod> latestPeriod(UUID chamaId) {
        return jdbc.query("select " + PERIOD_COLUMNS + " from contribution.periods where tenant_id = :tenant "
                        + "order by end_date desc limit 1", Map.of("tenant", chamaId), PERIOD).stream().findFirst();
    }

    @Override
    public List<ContributionPeriod> periods(UUID chamaId) {
        return jdbc.query("select " + PERIOD_COLUMNS + " from contribution.periods where tenant_id = :tenant "
                + "order by start_date", Map.of("tenant", chamaId), PERIOD);
    }

    @Override
    public Optional<ContributionPeriod> period(UUID chamaId, UUID periodId) {
        return jdbc.query("select " + PERIOD_COLUMNS + " from contribution.periods where tenant_id = :tenant and id = :id",
                Map.of("tenant", chamaId, "id", periodId), PERIOD).stream().findFirst();
    }

    @Override
    public boolean insertPeriod(ContributionPeriod p, Map<UUID, Long> amountPerProduct) {
        int inserted = jdbc.update("""
                insert into contribution.periods (id, tenant_id, period_key, frequency, start_date, end_date, due_date,
                                                  amount_due_minor, currency, opened_at)
                values (:id, :tenant, :key, :frequency, :start, :end, :due, :amount, :currency, :openedAt)
                on conflict (tenant_id, period_key) do nothing""",
                new MapSqlParameterSource()
                        .addValue("id", p.id())
                        .addValue("tenant", p.chamaId())
                        .addValue("key", p.key())
                        .addValue("frequency", p.frequency().name())
                        .addValue("start", Date.valueOf(p.startDate()))
                        .addValue("end", Date.valueOf(p.endDate()))
                        .addValue("due", Date.valueOf(p.dueDate()))
                        .addValue("amount", p.amountDueMinor())
                        .addValue("currency", p.currency())
                        .addValue("openedAt", Timestamp.from(p.openedAt())));
        if (inserted == 0) {
            return false;
        }
        MapSqlParameterSource[] rows = amountPerProduct.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .map(e -> new MapSqlParameterSource()
                        .addValue("tenant", p.chamaId())
                        .addValue("period", p.id())
                        .addValue("product", e.getKey())
                        .addValue("amount", e.getValue()))
                .toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate("""
                insert into contribution.period_products (tenant_id, period_id, product_id, amount_minor)
                values (:tenant, :period, :product, :amount)""", rows);
        return true;
    }

    @Override
    public Map<UUID, Map<UUID, Long>> periodProducts(UUID chamaId) {
        Map<UUID, Map<UUID, Long>> result = new HashMap<>();
        jdbc.query("select period_id, product_id, amount_minor from contribution.period_products where tenant_id = :tenant",
                Map.of("tenant", chamaId),
                rs -> {
                    result.computeIfAbsent(rs.getObject("period_id", UUID.class), k -> new HashMap<>())
                            .put(rs.getObject("product_id", UUID.class), rs.getLong("amount_minor"));
                });
        return result;
    }

    @Override
    public List<DueRow> dues(UUID chamaId) {
        return jdbc.query("select period_id, member_user_id, product_id, amount_minor from contribution.dues "
                + "where tenant_id = :tenant", Map.of("tenant", chamaId), DUE);
    }

    @Override
    public List<DueRow> duesOf(UUID chamaId, UUID memberUserId) {
        return jdbc.query("""
                        select period_id, member_user_id, product_id, amount_minor from contribution.dues
                         where tenant_id = :tenant and member_user_id = :member""",
                Map.of("tenant", chamaId, "member", memberUserId), DUE);
    }

    @Override
    public void insertDues(UUID chamaId, Collection<DueRow> dues) {
        Timestamp now = Timestamp.from(Instant.now());
        MapSqlParameterSource[] rows = dues.stream().map(d -> new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("tenant", chamaId)
                .addValue("period", d.periodId())
                .addValue("member", d.memberUserId())
                .addValue("product", d.productId())
                .addValue("amount", d.amountMinor())
                .addValue("createdAt", now)).toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate("""
                insert into contribution.dues (id, tenant_id, period_id, member_user_id, product_id, amount_minor,
                                               created_at)
                values (:id, :tenant, :period, :member, :product, :amount, :createdAt)
                on conflict (period_id, member_user_id, product_id) do nothing""", rows);
    }

    // ---- payments -----------------------------------------------------------------------------

    @Override
    public void insertPayment(ContributionPayment p, List<Allocation> allocations) {
        insertPayment(p);
        MapSqlParameterSource[] rows = allocations.stream().map(a -> new MapSqlParameterSource()
                .addValue("tenant", p.chamaId())
                .addValue("payment", p.id())
                .addValue("product", a.productId())
                .addValue("amount", a.amountMinor())).toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate("""
                insert into contribution.payment_allocations (tenant_id, payment_id, product_id, amount_minor)
                values (:tenant, :payment, :product, :amount)""", rows);
    }

    @Override
    public Map<UUID, List<Allocation>> allocations(UUID chamaId, Collection<UUID> paymentIds) {
        Map<UUID, List<Allocation>> result = new HashMap<>();
        if (paymentIds.isEmpty()) {
            return result;
        }
        jdbc.query("""
                        select payment_id, product_id, amount_minor from contribution.payment_allocations
                         where tenant_id = :tenant and payment_id in (:ids)""",
                Map.of("tenant", chamaId, "ids", paymentIds),
                rs -> {
                    result.computeIfAbsent(rs.getObject("payment_id", UUID.class), k -> new ArrayList<>())
                            .add(new Allocation(rs.getObject("product_id", UUID.class), rs.getLong("amount_minor")));
                });
        return result;
    }

    private void insertPayment(ContributionPayment p) {
        jdbc.update("""
                insert into contribution.payments (id, tenant_id, member_user_id, amount_minor, currency, method, reference,
                                                   paid_on, notes, status, ledger_entry_id, recorded_by, recorded_at)
                values (:id, :tenant, :member, :amount, :currency, :method, :reference, :paidOn, :notes, :status,
                        :entry, :recordedBy, :recordedAt)""",
                new MapSqlParameterSource()
                        .addValue("id", p.id())
                        .addValue("tenant", p.chamaId())
                        .addValue("member", p.memberUserId())
                        .addValue("amount", p.amountMinor())
                        .addValue("currency", p.currency())
                        .addValue("method", p.method().name())
                        .addValue("reference", p.reference())
                        .addValue("paidOn", Date.valueOf(p.paidOn()))
                        .addValue("notes", p.notes())
                        .addValue("status", p.status().name())
                        .addValue("entry", p.ledgerEntryId())
                        .addValue("recordedBy", p.recordedBy())
                        .addValue("recordedAt", Timestamp.from(p.recordedAt())));
    }

    @Override
    public boolean updateReversal(ContributionPayment p) {
        int updated = jdbc.update("""
                update contribution.payments
                   set status = :status, reversed_by = :by, reversed_at = :at, reversal_reason = :reason,
                       reversal_entry_id = :entry, version = version + 1
                 where tenant_id = :tenant and id = :id and version = :version and status = 'RECORDED'""",
                new MapSqlParameterSource()
                        .addValue("status", p.status().name())
                        .addValue("by", p.reversedBy())
                        .addValue("at", Timestamp.from(p.reversedAt()))
                        .addValue("reason", p.reversalReason())
                        .addValue("entry", p.reversalEntryId())
                        .addValue("tenant", p.chamaId())
                        .addValue("id", p.id())
                        .addValue("version", p.version()));
        return updated == 1;
    }

    @Override
    public Optional<ContributionPayment> payment(UUID chamaId, UUID paymentId) {
        return jdbc.query("select " + PAYMENT_COLUMNS + " from contribution.payments where tenant_id = :tenant and id = :id",
                Map.of("tenant", chamaId, "id", paymentId), PAYMENT).stream().findFirst();
    }

    @Override
    public boolean recordedReferenceExists(UUID chamaId, PaymentMethod method, String reference) {
        Boolean exists = jdbc.queryForObject("""
                        select exists (select 1 from contribution.payments
                                        where tenant_id = :tenant and method = :method and upper(reference) = upper(:reference)
                                          and status = 'RECORDED')""",
                Map.of("tenant", chamaId, "method", method.name(), "reference", reference), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public Page<ContributionPayment> payments(UUID chamaId, UUID memberUserId, LocalDate from, LocalDate to,
                                              ContributionPayment.Status status, Pageable pageable) {
        StringBuilder where = new StringBuilder(" where tenant_id = :tenant");
        MapSqlParameterSource params = new MapSqlParameterSource("tenant", chamaId);
        if (memberUserId != null) {
            where.append(" and member_user_id = :member");
            params.addValue("member", memberUserId);
        }
        if (from != null) {
            where.append(" and paid_on >= :from");
            params.addValue("from", Date.valueOf(from));
        }
        if (to != null) {
            where.append(" and paid_on <= :to");
            params.addValue("to", Date.valueOf(to));
        }
        if (status != null) {
            where.append(" and status = :status");
            params.addValue("status", status.name());
        }
        Long total = jdbc.queryForObject("select count(*) from contribution.payments" + where, params, Long.class);
        params.addValue("limit", pageable.getPageSize()).addValue("offset", pageable.getOffset());
        List<ContributionPayment> rows = jdbc.query("select " + PAYMENT_COLUMNS + " from contribution.payments" + where
                + " order by paid_on desc, recorded_at desc limit :limit offset :offset", params, PAYMENT);
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    @Override
    public Map<UUID, Map<UUID, Long>> paidTotals(UUID chamaId) {
        Map<UUID, Map<UUID, Long>> totals = new HashMap<>();
        jdbc.query("""
                        select p.member_user_id, a.product_id, sum(a.amount_minor) as paid
                          from contribution.payments p
                          join contribution.payment_allocations a on a.payment_id = p.id
                         where p.tenant_id = :tenant and p.status = 'RECORDED'
                         group by p.member_user_id, a.product_id""",
                Map.of("tenant", chamaId),
                rs -> {
                    totals.computeIfAbsent(rs.getObject("member_user_id", UUID.class), k -> new HashMap<>())
                            .put(rs.getObject("product_id", UUID.class), rs.getLong("paid"));
                });
        return totals;
    }

    @Override
    public Map<UUID, Long> paidTotals(UUID chamaId, UUID memberUserId) {
        Map<UUID, Long> totals = new HashMap<>();
        jdbc.query("""
                        select a.product_id, sum(a.amount_minor) as paid
                          from contribution.payments p
                          join contribution.payment_allocations a on a.payment_id = p.id
                         where p.tenant_id = :tenant and p.member_user_id = :member and p.status = 'RECORDED'
                         group by a.product_id""",
                Map.of("tenant", chamaId, "member", memberUserId),
                rs -> {
                    totals.put(rs.getObject("product_id", UUID.class), rs.getLong("paid"));
                });
        return totals;
    }

    @Override
    public Set<UUID> membersWithPayments(UUID chamaId) {
        return new HashSet<>(jdbc.query("select distinct member_user_id from contribution.payments where tenant_id = :tenant",
                Map.of("tenant", chamaId), (rs, i) -> rs.getObject("member_user_id", UUID.class)));
    }

    // ---- mapping ------------------------------------------------------------------------------

    private static final RowMapper<ContributionPeriod> PERIOD = (rs, i) -> new ContributionPeriod(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("period_key"),
            Frequency.valueOf(rs.getString("frequency")),
            rs.getDate("start_date").toLocalDate(),
            rs.getDate("end_date").toLocalDate(),
            rs.getDate("due_date").toLocalDate(),
            rs.getLong("amount_due_minor"),
            rs.getString("currency"),
            rs.getTimestamp("opened_at").toInstant());

    private static final RowMapper<DueRow> DUE = (rs, i) -> new DueRow(
            rs.getObject("period_id", UUID.class),
            rs.getObject("member_user_id", UUID.class),
            rs.getObject("product_id", UUID.class),
            rs.getLong("amount_minor"));

    private static final RowMapper<ContributionPayment> PAYMENT = (rs, i) -> new ContributionPayment(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("member_user_id", UUID.class),
            rs.getLong("amount_minor"),
            rs.getString("currency"),
            PaymentMethod.valueOf(rs.getString("method")),
            rs.getString("reference"),
            rs.getDate("paid_on").toLocalDate(),
            rs.getString("notes"),
            rs.getObject("recorded_by", UUID.class),
            rs.getTimestamp("recorded_at").toInstant(),
            rs.getObject("ledger_entry_id", UUID.class),
            ContributionPayment.Status.valueOf(rs.getString("status")),
            rs.getObject("reversed_by", UUID.class),
            rs.getTimestamp("reversed_at") == null ? null : rs.getTimestamp("reversed_at").toInstant(),
            rs.getString("reversal_reason"),
            rs.getObject("reversal_entry_id", UUID.class),
            rs.getLong("version"));
}
