package ke.chama.modules.loan.infrastructure.persistence;

import ke.chama.modules.loan.application.port.out.LoanRepository;
import ke.chama.modules.loan.domain.model.Loan;
import ke.chama.modules.loan.domain.model.LoanRepayment;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Plain JDBC, like the other money modules. Row-level security restricts every statement to the current chama. */
@Repository
class JdbcLoanRepository implements LoanRepository {

    private static final String COLUMNS = """
            id, tenant_id, borrower_user_id, principal_minor, interest_bp, interest_minor, term_months, purpose, currency,
            applied_on, required_guarantors, required_approvals, status, rejected_by, rejected_at, rejection_reason,
            cancelled_at, disbursed_on, disbursed_by, disbursement_method, disbursement_reference, disbursement_entry_id,
            repaid_minor, created_at, version""";
    private static final String OPEN = "('PENDING_GUARANTORS', 'PENDING_APPROVAL', 'APPROVED', 'ACTIVE')";

    private final NamedParameterJdbcTemplate jdbc;

    JdbcLoanRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Loan l) {
        jdbc.update("""
                insert into loan.loans (id, tenant_id, borrower_user_id, principal_minor, interest_bp, interest_minor,
                                        term_months, purpose, currency, applied_on, required_guarantors,
                                        required_approvals, status, disbursed_on, disbursed_by, disbursement_method,
                                        disbursement_reference, disbursement_entry_id, repaid_minor, created_at)
                values (:id, :tenant, :borrower, :principal, :interestBp, :interest, :term, :purpose, :currency,
                        :appliedOn, :requiredGuarantors, :requiredApprovals, :status, :disbursedOn, :disbursedBy,
                        :method, :reference, :entry, :repaid, :createdAt)""",
                new MapSqlParameterSource()
                        .addValue("id", l.id())
                        .addValue("tenant", l.chamaId())
                        .addValue("borrower", l.borrowerUserId())
                        .addValue("principal", l.principalMinor())
                        .addValue("interestBp", l.interestBasisPoints())
                        .addValue("interest", l.interestMinor())
                        .addValue("term", l.termMonths())
                        .addValue("purpose", l.purpose())
                        .addValue("currency", l.currency())
                        .addValue("appliedOn", Date.valueOf(l.appliedOn()))
                        .addValue("requiredGuarantors", l.requiredGuarantors())
                        .addValue("requiredApprovals", l.requiredApprovals())
                        .addValue("status", l.status().name())
                        .addValue("disbursedOn", l.disbursedOn() == null ? null : Date.valueOf(l.disbursedOn()))
                        .addValue("disbursedBy", l.disbursedBy())
                        .addValue("method", l.disbursementMethod())
                        .addValue("reference", l.disbursementReference())
                        .addValue("entry", l.disbursementEntryId())
                        .addValue("repaid", l.repaidMinor())
                        .addValue("createdAt", Timestamp.from(l.createdAt())));
        saveChildren(l);
    }

    @Override
    public boolean update(Loan l) {
        int rows = jdbc.update("""
                update loan.loans
                   set status = :status, rejected_by = :rejectedBy, rejected_at = :rejectedAt, rejection_reason = :reason,
                       cancelled_at = :cancelledAt, disbursed_on = :disbursedOn, disbursed_by = :disbursedBy,
                       disbursement_method = :method, disbursement_reference = :reference,
                       disbursement_entry_id = :entry, repaid_minor = :repaid, version = version + 1
                 where tenant_id = :tenant and id = :id and version = :version""",
                new MapSqlParameterSource()
                        .addValue("status", l.status().name())
                        .addValue("rejectedBy", l.rejectedBy())
                        .addValue("rejectedAt", timestamp(l.rejectedAt()))
                        .addValue("reason", l.rejectionReason())
                        .addValue("cancelledAt", timestamp(l.cancelledAt()))
                        .addValue("disbursedOn", l.disbursedOn() == null ? null : Date.valueOf(l.disbursedOn()))
                        .addValue("disbursedBy", l.disbursedBy())
                        .addValue("method", l.disbursementMethod())
                        .addValue("reference", l.disbursementReference())
                        .addValue("entry", l.disbursementEntryId())
                        .addValue("repaid", l.repaidMinor())
                        .addValue("tenant", l.chamaId())
                        .addValue("id", l.id())
                        .addValue("version", l.version()));
        if (rows != 1) {
            return false;
        }
        saveChildren(l);
        return true;
    }

    private void saveChildren(Loan l) {
        for (Loan.Guarantor g : l.guarantors()) {
            jdbc.update("""
                    insert into loan.guarantors (tenant_id, loan_id, guarantor_user_id, status, requested_at, responded_at)
                    values (:tenant, :loan, :user, :status, :requestedAt, :respondedAt)
                    on conflict (tenant_id, loan_id, guarantor_user_id)
                    do update set status = excluded.status, responded_at = excluded.responded_at
                     where loan.guarantors.status is distinct from excluded.status""",
                    new MapSqlParameterSource()
                            .addValue("tenant", l.chamaId())
                            .addValue("loan", l.id())
                            .addValue("user", g.userId())
                            .addValue("status", g.status().name())
                            .addValue("requestedAt", Timestamp.from(g.requestedAt()))
                            .addValue("respondedAt", timestamp(g.respondedAt())));
        }
        for (Loan.Approval a : l.approvals()) {
            jdbc.update("""
                    insert into loan.approvals (tenant_id, loan_id, approver_user_id, note, approved_at)
                    values (:tenant, :loan, :approver, :note, :approvedAt)
                    on conflict do nothing""",
                    new MapSqlParameterSource()
                            .addValue("tenant", l.chamaId())
                            .addValue("loan", l.id())
                            .addValue("approver", a.approverUserId())
                            .addValue("note", a.note())
                            .addValue("approvedAt", Timestamp.from(a.approvedAt())));
        }
    }

    @Override
    public Optional<Loan> loan(UUID chamaId, UUID loanId) {
        return hydrate(chamaId, jdbc.query("select " + COLUMNS + " from loan.loans where tenant_id = :tenant and id = :id",
                Map.of("tenant", chamaId, "id", loanId), (rs, i) -> row(rs))).stream().findFirst();
    }

    @Override
    public Page<Loan> loans(UUID chamaId, UUID borrowerUserId, Loan.Status status, Pageable pageable) {
        StringBuilder where = new StringBuilder(" where tenant_id = :tenant");
        MapSqlParameterSource params = new MapSqlParameterSource("tenant", chamaId);
        if (borrowerUserId != null) {
            where.append(" and borrower_user_id = :borrower");
            params.addValue("borrower", borrowerUserId);
        }
        if (status != null) {
            where.append(" and status = :status");
            params.addValue("status", status.name());
        }
        Long total = jdbc.queryForObject("select count(*) from loan.loans" + where, params, Long.class);
        params.addValue("limit", pageable.getPageSize()).addValue("offset", pageable.getOffset());
        List<Row> rows = jdbc.query("select " + COLUMNS + " from loan.loans" + where
                + " order by applied_on desc, created_at desc limit :limit offset :offset", params, (rs, i) -> row(rs));
        return new PageImpl<>(hydrate(chamaId, rows), pageable, total == null ? 0 : total);
    }

    @Override
    public List<Loan> loansOf(UUID chamaId, UUID borrowerUserId) {
        return hydrate(chamaId, jdbc.query("select " + COLUMNS + " from loan.loans"
                        + " where tenant_id = :tenant and borrower_user_id = :borrower order by applied_on desc, created_at desc",
                Map.of("tenant", chamaId, "borrower", borrowerUserId), (rs, i) -> row(rs)));
    }

    @Override
    public List<Loan> guaranteedBy(UUID chamaId, UUID guarantorUserId) {
        return hydrate(chamaId, jdbc.query("select " + COLUMNS + " from loan.loans l where tenant_id = :tenant"
                        + " and exists (select 1 from loan.guarantors g where g.tenant_id = l.tenant_id and g.loan_id = l.id"
                        + " and g.guarantor_user_id = :user) order by applied_on desc, created_at desc",
                Map.of("tenant", chamaId, "user", guarantorUserId), (rs, i) -> row(rs)));
    }

    @Override
    public boolean hasOpenLoan(UUID chamaId, UUID borrowerUserId) {
        Boolean exists = jdbc.queryForObject("select exists (select 1 from loan.loans where tenant_id = :tenant"
                        + " and borrower_user_id = :borrower and status in " + OPEN + ")",
                Map.of("tenant", chamaId, "borrower", borrowerUserId), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public Set<UUID> borrowersWithOpenLoans(UUID chamaId) {
        return new HashSet<>(jdbc.queryForList("select borrower_user_id from loan.loans where tenant_id = :tenant"
                + " and status in " + OPEN, Map.of("tenant", chamaId), UUID.class));
    }

    @Override
    public void insertRepayment(LoanRepayment r) {
        jdbc.update("""
                insert into loan.repayments (id, tenant_id, loan_id, borrower_user_id, amount_minor, currency, method,
                                             reference, paid_on, ledger_entry_id, recorded_by, recorded_at)
                values (:id, :tenant, :loan, :borrower, :amount, :currency, :method, :reference, :paidOn, :entry,
                        :recordedBy, :recordedAt)""",
                new MapSqlParameterSource()
                        .addValue("id", r.id())
                        .addValue("tenant", r.chamaId())
                        .addValue("loan", r.loanId())
                        .addValue("borrower", r.borrowerUserId())
                        .addValue("amount", r.amountMinor())
                        .addValue("currency", r.currency())
                        .addValue("method", r.method())
                        .addValue("reference", r.reference())
                        .addValue("paidOn", Date.valueOf(r.paidOn()))
                        .addValue("entry", r.ledgerEntryId())
                        .addValue("recordedBy", r.recordedBy())
                        .addValue("recordedAt", Timestamp.from(r.recordedAt())));
    }

    @Override
    public List<LoanRepayment> repayments(UUID chamaId, UUID loanId) {
        return jdbc.query("""
                        select id, tenant_id, loan_id, borrower_user_id, amount_minor, currency, method, reference, paid_on,
                               ledger_entry_id, recorded_by, recorded_at
                          from loan.repayments where tenant_id = :tenant and loan_id = :loan order by paid_on, recorded_at""",
                Map.of("tenant", chamaId, "loan", loanId),
                (rs, i) -> new LoanRepayment(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                        rs.getObject("loan_id", UUID.class), rs.getObject("borrower_user_id", UUID.class),
                        rs.getLong("amount_minor"), rs.getString("currency"), rs.getString("method"),
                        rs.getString("reference"), rs.getDate("paid_on").toLocalDate(),
                        rs.getObject("ledger_entry_id", UUID.class), rs.getObject("recorded_by", UUID.class),
                        rs.getTimestamp("recorded_at").toInstant()));
    }

    @Override
    public boolean referenceExists(UUID chamaId, String method, String reference) {
        Boolean exists = jdbc.queryForObject("""
                        select exists (select 1 from loan.repayments
                                        where tenant_id = :tenant and method = :method and upper(reference) = upper(:reference))
                            or exists (select 1 from loan.loans
                                        where tenant_id = :tenant and disbursement_method = :method
                                          and upper(disbursement_reference) = upper(:reference))""",
                Map.of("tenant", chamaId, "method", method, "reference", reference), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    // ---- mapping --------------------------------------------------------------------------------

    /** A loans row before its guarantors and approvals are attached. */
    private record Row(UUID id, UUID chamaId, UUID borrower, long principal, int interestBp, long interest, int term,
                       String purpose, String currency, LocalDate appliedOn, int requiredGuarantors,
                       int requiredApprovals, Loan.Status status, UUID rejectedBy, Instant rejectedAt, String reason,
                       Instant cancelledAt, LocalDate disbursedOn, UUID disbursedBy, String method, String reference,
                       UUID entry, long repaid, Instant createdAt, long version) {
    }

    private static Row row(ResultSet rs) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("borrower_user_id", UUID.class), rs.getLong("principal_minor"), rs.getInt("interest_bp"),
                rs.getLong("interest_minor"), rs.getInt("term_months"), rs.getString("purpose"), rs.getString("currency"),
                rs.getDate("applied_on").toLocalDate(), rs.getInt("required_guarantors"), rs.getInt("required_approvals"),
                Loan.Status.valueOf(rs.getString("status")), rs.getObject("rejected_by", UUID.class),
                instant(rs, "rejected_at"), rs.getString("rejection_reason"), instant(rs, "cancelled_at"),
                rs.getDate("disbursed_on") == null ? null : rs.getDate("disbursed_on").toLocalDate(),
                rs.getObject("disbursed_by", UUID.class), rs.getString("disbursement_method"),
                rs.getString("disbursement_reference"), rs.getObject("disbursement_entry_id", UUID.class),
                rs.getLong("repaid_minor"), rs.getTimestamp("created_at").toInstant(), rs.getLong("version"));
    }

    /** Loads the guarantors and approvals of all the rows in two queries. */
    private List<Loan> hydrate(UUID chamaId, List<Row> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = rows.stream().map(Row::id).toList();
        MapSqlParameterSource params = new MapSqlParameterSource("tenant", chamaId).addValue("ids", ids);
        Map<UUID, List<Loan.Guarantor>> guarantors = new HashMap<>();
        jdbc.query("""
                select loan_id, guarantor_user_id, status, requested_at, responded_at from loan.guarantors
                 where tenant_id = :tenant and loan_id in (:ids) order by requested_at, guarantor_user_id""", params, rs -> {
            guarantors.computeIfAbsent(rs.getObject("loan_id", UUID.class), k -> new ArrayList<>())
                    .add(new Loan.Guarantor(rs.getObject("guarantor_user_id", UUID.class),
                            Loan.Guarantor.Status.valueOf(rs.getString("status")),
                            rs.getTimestamp("requested_at").toInstant(), instant(rs, "responded_at")));
        });
        Map<UUID, List<Loan.Approval>> approvals = new HashMap<>();
        jdbc.query("""
                select loan_id, approver_user_id, note, approved_at from loan.approvals
                 where tenant_id = :tenant and loan_id in (:ids) order by approved_at""", params, rs -> {
            approvals.computeIfAbsent(rs.getObject("loan_id", UUID.class), k -> new ArrayList<>())
                    .add(new Loan.Approval(rs.getObject("approver_user_id", UUID.class), rs.getString("note"),
                            rs.getTimestamp("approved_at").toInstant()));
        });
        return rows.stream().map(r -> new Loan(r.id(), r.chamaId(), r.borrower(), r.principal(), r.interestBp(),
                r.interest(), r.term(), r.purpose(), r.currency(), r.appliedOn(), r.requiredGuarantors(),
                r.requiredApprovals(), guarantors.getOrDefault(r.id(), List.of()), approvals.getOrDefault(r.id(), List.of()),
                r.status(), r.rejectedBy(), r.rejectedAt(), r.reason(), r.cancelledAt(), r.disbursedOn(), r.disbursedBy(),
                r.method(), r.reference(), r.entry(), r.repaid(), r.createdAt(), r.version())).toList();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
