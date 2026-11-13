package ke.chama.modules.expense.infrastructure.persistence;

import ke.chama.modules.expense.application.port.out.ExpenseRepository;
import ke.chama.modules.expense.domain.model.Expense;
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
class JdbcExpenseRepository implements ExpenseRepository {

    private static final String COLUMNS = """
            id, tenant_id, amount_minor, currency, payee, description, account_code, investment_id, incurred_on,
            requested_by,
            required_approvals, status, rejected_by, rejected_at, rejection_reason, cancelled_at, paid_on, paid_by,
            payment_method, payment_reference, payment_entry_id, created_at, version""";

    private final NamedParameterJdbcTemplate jdbc;

    JdbcExpenseRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Expense e) {
        jdbc.update("""
                insert into expense.expenses (id, tenant_id, amount_minor, currency, payee, description, account_code,
                                              investment_id, incurred_on, requested_by, required_approvals, status,
                                              created_at)
                values (:id, :tenant, :amount, :currency, :payee, :description, :account, :investment, :incurredOn,
                        :requestedBy, :requiredApprovals, :status, :createdAt)""",
                new MapSqlParameterSource()
                        .addValue("id", e.id())
                        .addValue("tenant", e.chamaId())
                        .addValue("amount", e.amountMinor())
                        .addValue("currency", e.currency())
                        .addValue("payee", e.payee())
                        .addValue("description", e.description())
                        .addValue("account", e.accountCode())
                        .addValue("investment", e.investmentId())
                        .addValue("incurredOn", Date.valueOf(e.incurredOn()))
                        .addValue("requestedBy", e.requestedBy())
                        .addValue("requiredApprovals", e.requiredApprovals())
                        .addValue("status", e.status().name())
                        .addValue("createdAt", Timestamp.from(e.createdAt())));
        saveApprovals(e);
    }

    @Override
    public boolean update(Expense e) {
        int rows = jdbc.update("""
                update expense.expenses
                   set status = :status, rejected_by = :rejectedBy, rejected_at = :rejectedAt, rejection_reason = :reason,
                       cancelled_at = :cancelledAt, paid_on = :paidOn, paid_by = :paidBy, payment_method = :method,
                       payment_reference = :reference, payment_entry_id = :entry, version = version + 1
                 where tenant_id = :tenant and id = :id and version = :version""",
                new MapSqlParameterSource()
                        .addValue("status", e.status().name())
                        .addValue("rejectedBy", e.rejectedBy())
                        .addValue("rejectedAt", timestamp(e.rejectedAt()))
                        .addValue("reason", e.rejectionReason())
                        .addValue("cancelledAt", timestamp(e.cancelledAt()))
                        .addValue("paidOn", e.paidOn() == null ? null : Date.valueOf(e.paidOn()))
                        .addValue("paidBy", e.paidBy())
                        .addValue("method", e.paymentMethod())
                        .addValue("reference", e.paymentReference())
                        .addValue("entry", e.paymentEntryId())
                        .addValue("tenant", e.chamaId())
                        .addValue("id", e.id())
                        .addValue("version", e.version()));
        if (rows != 1) {
            return false;
        }
        saveApprovals(e);
        return true;
    }

    private void saveApprovals(Expense e) {
        for (Expense.Approval a : e.approvals()) {
            jdbc.update("""
                    insert into expense.approvals (tenant_id, expense_id, approver_user_id, note, approved_at)
                    values (:tenant, :expense, :approver, :note, :approvedAt)
                    on conflict do nothing""",
                    new MapSqlParameterSource()
                            .addValue("tenant", e.chamaId())
                            .addValue("expense", e.id())
                            .addValue("approver", a.approverUserId())
                            .addValue("note", a.note())
                            .addValue("approvedAt", Timestamp.from(a.approvedAt())));
        }
    }

    @Override
    public Optional<Expense> expense(UUID chamaId, UUID expenseId) {
        return hydrate(chamaId, jdbc.query("select " + COLUMNS + " from expense.expenses where tenant_id = :tenant and id = :id",
                Map.of("tenant", chamaId, "id", expenseId), (rs, i) -> row(rs))).stream().findFirst();
    }

    @Override
    public Page<Expense> expenses(UUID chamaId, Expense.Status status, UUID investmentId, LocalDate from, LocalDate to,
                                  Pageable pageable) {
        StringBuilder where = new StringBuilder(" where tenant_id = :tenant");
        MapSqlParameterSource params = new MapSqlParameterSource("tenant", chamaId);
        if (status != null) {
            where.append(" and status = :status");
            params.addValue("status", status.name());
        }
        if (investmentId != null) {
            where.append(" and investment_id = :investment");
            params.addValue("investment", investmentId);
        }
        if (from != null) {
            where.append(" and incurred_on >= :from");
            params.addValue("from", Date.valueOf(from));
        }
        if (to != null) {
            where.append(" and incurred_on <= :to");
            params.addValue("to", Date.valueOf(to));
        }
        Long total = jdbc.queryForObject("select count(*) from expense.expenses" + where, params, Long.class);
        params.addValue("limit", pageable.getPageSize()).addValue("offset", pageable.getOffset());
        List<Row> rows = jdbc.query("select " + COLUMNS + " from expense.expenses" + where
                + " order by incurred_on desc, created_at desc limit :limit offset :offset", params, (rs, i) -> row(rs));
        return new PageImpl<>(hydrate(chamaId, rows), pageable, total == null ? 0 : total);
    }

    @Override
    public boolean referenceExists(UUID chamaId, String method, String reference) {
        Boolean exists = jdbc.queryForObject("""
                        select exists (select 1 from expense.expenses where tenant_id = :tenant and payment_method = :method
                                          and upper(payment_reference) = upper(:reference))""",
                Map.of("tenant", chamaId, "method", method, "reference", reference), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public List<AccountTotal> paidByAccount(UUID chamaId, LocalDate from, LocalDate to) {
        return jdbc.query("""
                        select account_code, sum(amount_minor) as total, count(*) as n from expense.expenses
                         where tenant_id = :tenant and status = 'PAID' and paid_on between :from and :to
                         group by account_code order by total desc, account_code""",
                new MapSqlParameterSource("tenant", chamaId).addValue("from", Date.valueOf(from)).addValue("to", Date.valueOf(to)),
                (rs, i) -> new AccountTotal(rs.getString("account_code"), rs.getLong("total"), rs.getInt("n")));
    }

    @Override
    public Map<UUID, Long> paidByInvestment(UUID chamaId) {
        Map<UUID, Long> totals = new HashMap<>();
        jdbc.query("""
                        select investment_id, sum(amount_minor) as total from expense.expenses
                         where tenant_id = :tenant and status = 'PAID' and investment_id is not null
                         group by investment_id""",
                Map.of("tenant", chamaId),
                rs -> {
                    totals.put(rs.getObject("investment_id", UUID.class), rs.getLong("total"));
                });
        return totals;
    }

    @Override
    public long awaitingPaymentMinor(UUID chamaId) {
        Long total = jdbc.queryForObject("""
                        select coalesce(sum(amount_minor), 0) from expense.expenses
                         where tenant_id = :tenant and status in ('PENDING_APPROVAL', 'APPROVED')""",
                Map.of("tenant", chamaId), Long.class);
        return total == null ? 0 : total;
    }

    // ---- mapping --------------------------------------------------------------------------------

    /** An expenses row before its approvals are attached. */
    private record Row(UUID id, UUID chamaId, long amount, String currency, String payee, String description,
                       String account, UUID investment, LocalDate incurredOn, UUID requestedBy, int requiredApprovals,
                       Expense.Status status, UUID rejectedBy, Instant rejectedAt, String reason, Instant cancelledAt,
                       LocalDate paidOn, UUID paidBy, String method, String reference, UUID entry, Instant createdAt,
                       long version) {
    }

    private static Row row(ResultSet rs) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getLong("amount_minor"),
                rs.getString("currency"), rs.getString("payee"), rs.getString("description"),
                rs.getString("account_code"), rs.getObject("investment_id", UUID.class),
                rs.getDate("incurred_on").toLocalDate(),
                rs.getObject("requested_by", UUID.class), rs.getInt("required_approvals"),
                Expense.Status.valueOf(rs.getString("status")), rs.getObject("rejected_by", UUID.class),
                instant(rs, "rejected_at"), rs.getString("rejection_reason"), instant(rs, "cancelled_at"),
                rs.getDate("paid_on") == null ? null : rs.getDate("paid_on").toLocalDate(),
                rs.getObject("paid_by", UUID.class), rs.getString("payment_method"), rs.getString("payment_reference"),
                rs.getObject("payment_entry_id", UUID.class), rs.getTimestamp("created_at").toInstant(),
                rs.getLong("version"));
    }

    /** Loads the approvals of all the rows in one query. */
    private List<Expense> hydrate(UUID chamaId, List<Row> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<Expense.Approval>> approvals = new HashMap<>();
        jdbc.query("""
                        select expense_id, approver_user_id, note, approved_at from expense.approvals
                         where tenant_id = :tenant and expense_id in (:ids) order by approved_at""",
                new MapSqlParameterSource("tenant", chamaId).addValue("ids", rows.stream().map(Row::id).toList()),
                rs -> {
                    approvals.computeIfAbsent(rs.getObject("expense_id", UUID.class), k -> new ArrayList<>())
                            .add(new Expense.Approval(rs.getObject("approver_user_id", UUID.class), rs.getString("note"),
                                    rs.getTimestamp("approved_at").toInstant()));
                });
        return rows.stream().map(r -> new Expense(r.id(), r.chamaId(), r.amount(), r.currency(), r.payee(),
                r.description(), r.account(), r.investment(), r.incurredOn(), r.requestedBy(), r.requiredApprovals(),
                approvals.getOrDefault(r.id(), List.of()), r.status(), r.rejectedBy(), r.rejectedAt(), r.reason(),
                r.cancelledAt(), r.paidOn(), r.paidBy(), r.method(), r.reference(), r.entry(), r.createdAt(),
                r.version())).toList();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    // ---- history brought over from spreadsheets -------------------------------------------------

    @Override
    public void markImported(UUID chamaId, java.util.Collection<UUID> expenseIds, UUID batchId) {
        if (!expenseIds.isEmpty()) {
            jdbc.update("update expense.expenses set import_batch_id = :batch where tenant_id = :tenant and id in (:ids)",
                    new MapSqlParameterSource().addValue("batch", batchId).addValue("tenant", chamaId)
                            .addValue("ids", expenseIds));
        }
    }

    @Override
    public List<Expense> importedExpenses(UUID chamaId, UUID batchId) {
        return hydrate(chamaId, jdbc.query("select " + COLUMNS + " from expense.expenses"
                        + " where tenant_id = :tenant and import_batch_id = :batch",
                Map.of("tenant", chamaId, "batch", batchId), (rs, i) -> row(rs)));
    }

    @Override
    public void deleteImported(UUID chamaId, UUID batchId) {
        jdbc.update("delete from expense.expenses where tenant_id = :tenant and import_batch_id = :batch",
                Map.of("tenant", chamaId, "batch", batchId));
    }

    @Override
    public java.util.Set<String> usedReferences(UUID chamaId) {
        return new java.util.HashSet<>(jdbc.queryForList("""
                        select payment_method || ':' || upper(payment_reference) from expense.expenses
                         where tenant_id = :tenant and payment_reference is not null""",
                Map.of("tenant", chamaId), String.class));
    }
}
