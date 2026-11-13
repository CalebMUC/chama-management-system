package ke.chama.modules.investment.infrastructure.persistence;

import ke.chama.modules.investment.application.port.out.InvestmentRepository;
import ke.chama.modules.investment.domain.model.Investment;
import ke.chama.modules.investment.domain.model.InvestmentTransaction;
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
class JdbcInvestmentRepository implements InvestmentRepository {

    private static final String COLUMNS = """
            id, tenant_id, name, type, institution, description, amount_minor, currency, expected_return_bp,
            maturity_date, proposed_by, required_approvals, status, rejected_by, rejected_at, rejection_reason,
            cancelled_at, funded_on, book_value_minor, income_minor, withdrawn_minor, deposited_minor,
            latest_valuation_minor,
            latest_valuation_on, closed_on, closing_proceeds_minor, realised_gain_minor, created_at, version""";

    private final NamedParameterJdbcTemplate jdbc;

    JdbcInvestmentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Investment i) {
        jdbc.update("""
                insert into investment.investments (id, tenant_id, name, type, institution, description, amount_minor,
                                                    currency, expected_return_bp, maturity_date, proposed_by,
                                                    required_approvals, status, created_at)
                values (:id, :tenant, :name, :type, :institution, :description, :amount, :currency, :expectedReturn,
                        :maturity, :proposedBy, :requiredApprovals, :status, :createdAt)""",
                new MapSqlParameterSource()
                        .addValue("id", i.id())
                        .addValue("tenant", i.chamaId())
                        .addValue("name", i.name())
                        .addValue("type", i.type().name())
                        .addValue("institution", i.institution())
                        .addValue("description", i.description())
                        .addValue("amount", i.amountMinor())
                        .addValue("currency", i.currency())
                        .addValue("expectedReturn", i.expectedReturnBasisPoints())
                        .addValue("maturity", date(i.maturityDate()))
                        .addValue("proposedBy", i.proposedBy())
                        .addValue("requiredApprovals", i.requiredApprovals())
                        .addValue("status", i.status().name())
                        .addValue("createdAt", Timestamp.from(i.createdAt())));
        saveApprovals(i);
    }

    @Override
    public boolean update(Investment i) {
        int rows = jdbc.update("""
                update investment.investments
                   set status = :status, rejected_by = :rejectedBy, rejected_at = :rejectedAt, rejection_reason = :reason,
                       cancelled_at = :cancelledAt, funded_on = :fundedOn, book_value_minor = :book,
                       income_minor = :income, withdrawn_minor = :withdrawn, deposited_minor = :deposited,
                       latest_valuation_minor = :valuation,
                       latest_valuation_on = :valuationOn, closed_on = :closedOn, closing_proceeds_minor = :proceeds,
                       realised_gain_minor = :gain, version = version + 1
                 where tenant_id = :tenant and id = :id and version = :version""",
                new MapSqlParameterSource()
                        .addValue("status", i.status().name())
                        .addValue("rejectedBy", i.rejectedBy())
                        .addValue("rejectedAt", timestamp(i.rejectedAt()))
                        .addValue("reason", i.rejectionReason())
                        .addValue("cancelledAt", timestamp(i.cancelledAt()))
                        .addValue("fundedOn", date(i.fundedOn()))
                        .addValue("book", i.bookValueMinor())
                        .addValue("income", i.incomeMinor())
                        .addValue("withdrawn", i.withdrawnMinor())
                        .addValue("deposited", i.depositedMinor())
                        .addValue("valuation", i.latestValuationMinor())
                        .addValue("valuationOn", date(i.latestValuationOn()))
                        .addValue("closedOn", date(i.closedOn()))
                        .addValue("proceeds", i.closingProceedsMinor())
                        .addValue("gain", i.realisedGainMinor())
                        .addValue("tenant", i.chamaId())
                        .addValue("id", i.id())
                        .addValue("version", i.version()));
        if (rows != 1) {
            return false;
        }
        saveApprovals(i);
        return true;
    }

    private void saveApprovals(Investment i) {
        for (Investment.Approval a : i.approvals()) {
            jdbc.update("""
                    insert into investment.approvals (tenant_id, investment_id, approver_user_id, note, approved_at)
                    values (:tenant, :investment, :approver, :note, :approvedAt)
                    on conflict do nothing""",
                    new MapSqlParameterSource()
                            .addValue("tenant", i.chamaId())
                            .addValue("investment", i.id())
                            .addValue("approver", a.approverUserId())
                            .addValue("note", a.note())
                            .addValue("approvedAt", Timestamp.from(a.approvedAt())));
        }
    }

    @Override
    public Optional<Investment> investment(UUID chamaId, UUID investmentId) {
        return hydrate(chamaId, jdbc.query("select " + COLUMNS + " from investment.investments"
                        + " where tenant_id = :tenant and id = :id",
                Map.of("tenant", chamaId, "id", investmentId), (rs, n) -> row(rs))).stream().findFirst();
    }

    @Override
    public Page<Investment> investments(UUID chamaId, Investment.Status status, Investment.Type type, Pageable pageable) {
        StringBuilder where = new StringBuilder(" where tenant_id = :tenant");
        MapSqlParameterSource params = new MapSqlParameterSource("tenant", chamaId);
        if (status != null) {
            where.append(" and status = :status");
            params.addValue("status", status.name());
        }
        if (type != null) {
            where.append(" and type = :type");
            params.addValue("type", type.name());
        }
        Long total = jdbc.queryForObject("select count(*) from investment.investments" + where, params, Long.class);
        params.addValue("limit", pageable.getPageSize()).addValue("offset", pageable.getOffset());
        List<Row> rows = jdbc.query("select " + COLUMNS + " from investment.investments" + where
                + " order by created_at desc limit :limit offset :offset", params, (rs, n) -> row(rs));
        return new PageImpl<>(hydrate(chamaId, rows), pageable, total == null ? 0 : total);
    }

    @Override
    public List<Investment> fundedInvestments(UUID chamaId) {
        return hydrate(chamaId, jdbc.query("select " + COLUMNS + " from investment.investments"
                        + " where tenant_id = :tenant and status in ('ACTIVE', 'CLOSED') order by created_at desc",
                Map.of("tenant", chamaId), (rs, n) -> row(rs)));
    }

    @Override
    public void insertTransaction(InvestmentTransaction t) {
        jdbc.update("""
                insert into investment.transactions (id, tenant_id, investment_id, kind, amount_minor, currency, method,
                                                     reference, transaction_on, note, ledger_entry_id, recorded_by,
                                                     recorded_at)
                values (:id, :tenant, :investment, :kind, :amount, :currency, :method, :reference, :on, :note, :entry,
                        :recordedBy, :recordedAt)""",
                new MapSqlParameterSource()
                        .addValue("id", t.id())
                        .addValue("tenant", t.chamaId())
                        .addValue("investment", t.investmentId())
                        .addValue("kind", t.kind().name())
                        .addValue("amount", t.amountMinor())
                        .addValue("currency", t.currency())
                        .addValue("method", t.method())
                        .addValue("reference", t.reference())
                        .addValue("on", Date.valueOf(t.date()))
                        .addValue("note", t.note())
                        .addValue("entry", t.ledgerEntryId())
                        .addValue("recordedBy", t.recordedBy())
                        .addValue("recordedAt", Timestamp.from(t.recordedAt())));
    }

    @Override
    public List<InvestmentTransaction> transactions(UUID chamaId, UUID investmentId) {
        return jdbc.query("""
                        select id, tenant_id, investment_id, kind, amount_minor, currency, method, reference, transaction_on,
                               note, ledger_entry_id, recorded_by, recorded_at
                          from investment.transactions where tenant_id = :tenant and investment_id = :investment
                         order by transaction_on, recorded_at""",
                Map.of("tenant", chamaId, "investment", investmentId),
                (rs, n) -> new InvestmentTransaction(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                        rs.getObject("investment_id", UUID.class),
                        InvestmentTransaction.Kind.valueOf(rs.getString("kind")), rs.getLong("amount_minor"),
                        rs.getString("currency"), rs.getString("method"), rs.getString("reference"),
                        rs.getDate("transaction_on").toLocalDate(), rs.getString("note"),
                        rs.getObject("ledger_entry_id", UUID.class), rs.getObject("recorded_by", UUID.class),
                        rs.getTimestamp("recorded_at").toInstant()));
    }

    @Override
    public void insertValuation(Investment.Valuation v) {
        jdbc.update("""
                insert into investment.valuations (id, tenant_id, investment_id, value_minor, valued_on, note, recorded_by,
                                                   recorded_at)
                values (:id, :tenant, :investment, :value, :valuedOn, :note, :recordedBy, :recordedAt)""",
                new MapSqlParameterSource()
                        .addValue("id", v.id())
                        .addValue("tenant", v.chamaId())
                        .addValue("investment", v.investmentId())
                        .addValue("value", v.valueMinor())
                        .addValue("valuedOn", Date.valueOf(v.valuedOn()))
                        .addValue("note", v.note())
                        .addValue("recordedBy", v.recordedBy())
                        .addValue("recordedAt", Timestamp.from(v.recordedAt())));
    }

    @Override
    public List<Investment.Valuation> valuations(UUID chamaId, UUID investmentId) {
        return jdbc.query("""
                        select id, tenant_id, investment_id, value_minor, valued_on, note, recorded_by, recorded_at
                          from investment.valuations where tenant_id = :tenant and investment_id = :investment
                         order by valued_on, recorded_at""",
                Map.of("tenant", chamaId, "investment", investmentId),
                (rs, n) -> new Investment.Valuation(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                        rs.getObject("investment_id", UUID.class), rs.getLong("value_minor"),
                        rs.getDate("valued_on").toLocalDate(), rs.getString("note"),
                        rs.getObject("recorded_by", UUID.class), rs.getTimestamp("recorded_at").toInstant()));
    }

    @Override
    public boolean referenceExists(UUID chamaId, String method, String reference) {
        Boolean exists = jdbc.queryForObject("""
                        select exists (select 1 from investment.transactions where tenant_id = :tenant and method = :method
                                          and upper(reference) = upper(:reference))""",
                Map.of("tenant", chamaId, "method", method, "reference", reference), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    // ---- history brought over from spreadsheets -------------------------------------------------

    @Override
    public void markImported(UUID chamaId, java.util.Collection<UUID> investmentIds, UUID batchId) {
        if (investmentIds.isEmpty()) {
            return;
        }
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("batch", batchId).addValue("tenant", chamaId)
                .addValue("ids", investmentIds);
        jdbc.update("update investment.investments set import_batch_id = :batch where tenant_id = :tenant and id in (:ids)",
                params);
        jdbc.update("""
                update investment.transactions set import_batch_id = :batch
                 where tenant_id = :tenant and investment_id in (:ids)""", params);
        jdbc.update("""
                update investment.valuations set import_batch_id = :batch
                 where tenant_id = :tenant and investment_id in (:ids)""", params);
    }

    @Override
    public List<Investment> importedInvestments(UUID chamaId, UUID batchId) {
        return hydrate(chamaId, jdbc.query("select " + COLUMNS + " from investment.investments"
                        + " where tenant_id = :tenant and import_batch_id = :batch",
                Map.of("tenant", chamaId, "batch", batchId), (rs, i) -> row(rs)));
    }

    @Override
    public long laterActivity(UUID chamaId, UUID batchId) {
        Long count = jdbc.queryForObject("""
                        select (select count(*) from investment.transactions t
                                  join investment.investments i on i.tenant_id = t.tenant_id and i.id = t.investment_id
                                 where t.tenant_id = :tenant and i.import_batch_id = :batch
                                   and (t.import_batch_id is null or t.import_batch_id <> :batch))
                             + (select count(*) from investment.valuations v
                                  join investment.investments i on i.tenant_id = v.tenant_id and i.id = v.investment_id
                                 where v.tenant_id = :tenant and i.import_batch_id = :batch
                                   and (v.import_batch_id is null or v.import_batch_id <> :batch))""",
                Map.of("tenant", chamaId, "batch", batchId), Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public void deleteImported(UUID chamaId, UUID batchId) {
        Map<String, Object> params = Map.of("tenant", chamaId, "batch", batchId);
        jdbc.update("""
                delete from investment.transactions t using investment.investments i
                 where t.tenant_id = :tenant and i.tenant_id = :tenant and t.investment_id = i.id
                   and i.import_batch_id = :batch""", params);
        jdbc.update("""
                delete from investment.valuations v using investment.investments i
                 where v.tenant_id = :tenant and i.tenant_id = :tenant and v.investment_id = i.id
                   and i.import_batch_id = :batch""", params);
        jdbc.update("delete from investment.investments where tenant_id = :tenant and import_batch_id = :batch", params);
    }

    @Override
    public java.util.Set<String> usedReferences(UUID chamaId) {
        return new java.util.HashSet<>(jdbc.queryForList("""
                        select method || ':' || upper(reference) from investment.transactions
                         where tenant_id = :tenant and reference is not null""",
                Map.of("tenant", chamaId), String.class));
    }

    // ---- mapping --------------------------------------------------------------------------------

    /** An investments row before its approvals are attached. */
    private record Row(UUID id, UUID chamaId, String name, Investment.Type type, String institution,
                       String description, long amount, String currency, Integer expectedReturn, LocalDate maturity,
                       UUID proposedBy, int requiredApprovals, Investment.Status status, UUID rejectedBy,
                       Instant rejectedAt, String reason, Instant cancelledAt, LocalDate fundedOn, long book,
                       long income, long withdrawn, long deposited, Long valuation, LocalDate valuationOn, LocalDate closedOn,
                       Long proceeds, Long gain, Instant createdAt, long version) {
    }

    private static Row row(ResultSet rs) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getString("name"),
                Investment.Type.valueOf(rs.getString("type")), rs.getString("institution"), rs.getString("description"),
                rs.getLong("amount_minor"), rs.getString("currency"), rs.getObject("expected_return_bp", Integer.class),
                localDate(rs, "maturity_date"), rs.getObject("proposed_by", UUID.class), rs.getInt("required_approvals"),
                Investment.Status.valueOf(rs.getString("status")), rs.getObject("rejected_by", UUID.class),
                instant(rs, "rejected_at"), rs.getString("rejection_reason"), instant(rs, "cancelled_at"),
                localDate(rs, "funded_on"), rs.getLong("book_value_minor"), rs.getLong("income_minor"),
                rs.getLong("withdrawn_minor"), rs.getLong("deposited_minor"),
                rs.getObject("latest_valuation_minor", Long.class),
                localDate(rs, "latest_valuation_on"), localDate(rs, "closed_on"),
                rs.getObject("closing_proceeds_minor", Long.class), rs.getObject("realised_gain_minor", Long.class),
                rs.getTimestamp("created_at").toInstant(), rs.getLong("version"));
    }

    private List<Investment> hydrate(UUID chamaId, List<Row> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<Investment.Approval>> approvals = new HashMap<>();
        jdbc.query("""
                        select investment_id, approver_user_id, note, approved_at from investment.approvals
                         where tenant_id = :tenant and investment_id in (:ids) order by approved_at""",
                new MapSqlParameterSource("tenant", chamaId).addValue("ids", rows.stream().map(Row::id).toList()),
                rs -> {
                    approvals.computeIfAbsent(rs.getObject("investment_id", UUID.class), k -> new ArrayList<>())
                            .add(new Investment.Approval(rs.getObject("approver_user_id", UUID.class), rs.getString("note"),
                                    rs.getTimestamp("approved_at").toInstant()));
                });
        return rows.stream().map(r -> new Investment(r.id(), r.chamaId(), r.name(), r.type(), r.institution(),
                r.description(), r.amount(), r.currency(), r.expectedReturn(), r.maturity(), r.proposedBy(),
                r.requiredApprovals(), approvals.getOrDefault(r.id(), List.of()), r.status(), r.rejectedBy(),
                r.rejectedAt(), r.reason(), r.cancelledAt(), r.fundedOn(), r.book(), r.income(), r.withdrawn(), r.deposited(),
                r.valuation(), r.valuationOn(), r.closedOn(), r.proceeds(), r.gain(), r.createdAt(), r.version())).toList();
    }

    private static LocalDate localDate(ResultSet rs, String column) throws SQLException {
        Date d = rs.getDate(column);
        return d == null ? null : d.toLocalDate();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static Date date(LocalDate date) {
        return date == null ? null : Date.valueOf(date);
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
