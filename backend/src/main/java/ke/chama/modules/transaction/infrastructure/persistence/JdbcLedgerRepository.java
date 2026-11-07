package ke.chama.modules.transaction.infrastructure.persistence;

import ke.chama.modules.transaction.api.EntrySide;
import ke.chama.modules.transaction.api.LedgerAccountKey;
import ke.chama.modules.transaction.application.port.out.LedgerRepository;
import ke.chama.modules.transaction.domain.model.AccountType;
import ke.chama.modules.transaction.domain.model.JournalEntry;
import ke.chama.modules.transaction.domain.model.JournalLine;
import ke.chama.modules.transaction.domain.model.LedgerAccount;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Plain JDBC: the ledger is insert-and-aggregate, which SQL expresses directly. Row-level
 * security restricts every statement to the current chama; {@code tenant_id} is still matched
 * explicitly so each query reads correctly on its own.
 */
@Repository
class JdbcLedgerRepository implements LedgerRepository {

    private static final String ACCOUNT_COLUMNS =
            "id, tenant_id, code, name, type, system_key, currency, member_ledger, active, created_by, created_at";

    private final NamedParameterJdbcTemplate jdbc;

    JdbcLedgerRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- accounts ---------------------------------------------------------------------------

    @Override
    public void insertAccounts(Collection<LedgerAccount> accounts) {
        MapSqlParameterSource[] rows = accounts.stream().map(a -> new MapSqlParameterSource()
                .addValue("id", a.id())
                .addValue("tenant", a.chamaId())
                .addValue("code", a.code())
                .addValue("name", a.name())
                .addValue("type", a.type().name())
                .addValue("key", a.systemKey() == null ? null : a.systemKey().name())
                .addValue("currency", a.currency().getCurrencyCode())
                .addValue("memberLedger", a.memberLedger())
                .addValue("active", a.active())
                .addValue("createdBy", a.createdBy())
                .addValue("createdAt", Timestamp.from(a.createdAt()))).toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate("""
                insert into ledger.accounts (id, tenant_id, code, name, type, system_key, currency, member_ledger,
                                             active, created_by, created_at)
                values (:id, :tenant, :code, :name, :type, :key, :currency, :memberLedger, :active, :createdBy, :createdAt)""",
                rows);
    }

    @Override
    public List<LedgerAccount> accounts(UUID chamaId) {
        return jdbc.query("select " + ACCOUNT_COLUMNS + " from ledger.accounts where tenant_id = :tenant order by code",
                Map.of("tenant", chamaId), ACCOUNT);
    }

    @Override
    public Optional<LedgerAccount> accountById(UUID chamaId, UUID accountId) {
        return jdbc.query("select " + ACCOUNT_COLUMNS + " from ledger.accounts where tenant_id = :tenant and id = :id",
                Map.of("tenant", chamaId, "id", accountId), ACCOUNT).stream().findFirst();
    }

    @Override
    public Optional<LedgerAccount> accountByKey(UUID chamaId, LedgerAccountKey key) {
        return jdbc.query("select " + ACCOUNT_COLUMNS + " from ledger.accounts where tenant_id = :tenant and system_key = :key",
                Map.of("tenant", chamaId, "key", key.name()), ACCOUNT).stream().findFirst();
    }

    @Override
    public Optional<LedgerAccount> accountByCode(UUID chamaId, String code) {
        return jdbc.query("select " + ACCOUNT_COLUMNS + " from ledger.accounts where tenant_id = :tenant and code = :code",
                Map.of("tenant", chamaId, "code", code), ACCOUNT).stream().findFirst();
    }

    @Override
    public void setAccountActive(UUID chamaId, UUID accountId, boolean active) {
        jdbc.update("update ledger.accounts set active = :active where tenant_id = :tenant and id = :id",
                Map.of("active", active, "tenant", chamaId, "id", accountId));
    }

    // ---- entries ----------------------------------------------------------------------------

    @Override
    public void initSequence(UUID chamaId) {
        jdbc.update("insert into ledger.entry_sequences (tenant_id, last_number) values (:tenant, 0) on conflict do nothing",
                Map.of("tenant", chamaId));
    }

    /** The row lock held until commit serialises numbering per chama; a rollback gives the number back. */
    @Override
    public long nextEntryNumber(UUID chamaId) {
        Long next = jdbc.queryForObject("""
                insert into ledger.entry_sequences (tenant_id, last_number) values (:tenant, 1)
                on conflict (tenant_id) do update set last_number = ledger.entry_sequences.last_number + 1
                returning last_number""", Map.of("tenant", chamaId), Long.class);
        return next == null ? 1 : next;
    }

    @Override
    public void insertEntry(JournalEntry entry) {
        jdbc.update("""
                insert into ledger.journal_entries (id, tenant_id, entry_number, entry_date, description, reference,
                                                    source_type, source_id, reverses_entry_id, recorded_by, recorded_at)
                values (:id, :tenant, :number, :date, :description, :reference, :sourceType, :sourceId, :reverses,
                        :recordedBy, :recordedAt)""",
                new MapSqlParameterSource()
                        .addValue("id", entry.id())
                        .addValue("tenant", entry.chamaId())
                        .addValue("number", entry.number())
                        .addValue("date", Date.valueOf(entry.entryDate()))
                        .addValue("description", entry.description())
                        .addValue("reference", entry.reference())
                        .addValue("sourceType", entry.sourceType())
                        .addValue("sourceId", entry.sourceId())
                        .addValue("reverses", entry.reversesEntryId())
                        .addValue("recordedBy", entry.recordedBy())
                        .addValue("recordedAt", Timestamp.from(entry.recordedAt())));
        List<MapSqlParameterSource> lines = new ArrayList<>();
        int number = 1;
        for (JournalLine line : entry.lines()) {
            lines.add(new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID())
                    .addValue("tenant", entry.chamaId())
                    .addValue("entry", entry.id())
                    .addValue("number", number++)
                    .addValue("account", line.accountId())
                    .addValue("debit", line.debitMinor())
                    .addValue("credit", line.creditMinor())
                    .addValue("member", line.memberUserId())
                    .addValue("memo", line.memo()));
        }
        jdbc.batchUpdate("""
                insert into ledger.journal_lines (id, tenant_id, entry_id, line_number, account_id, debit_minor,
                                                  credit_minor, member_user_id, memo)
                values (:id, :tenant, :entry, :number, :account, :debit, :credit, :member, :memo)""",
                lines.toArray(MapSqlParameterSource[]::new));
    }

    @Override
    public Optional<JournalEntry> entry(UUID chamaId, UUID entryId) {
        List<EntryRow> rows = jdbc.query(ENTRY_SELECT + " where tenant_id = :tenant and id = :id",
                Map.of("tenant", chamaId, "id", entryId), ENTRY_ROW);
        return rows.isEmpty() ? Optional.empty() : Optional.of(withLines(chamaId, rows).getFirst());
    }

    @Override
    public Optional<UUID> entryIdForSource(UUID chamaId, String sourceType, String sourceId) {
        return jdbc.query("""
                        select id from ledger.journal_entries
                         where tenant_id = :tenant and source_type = :type and source_id = :source""",
                Map.of("tenant", chamaId, "type", sourceType, "source", sourceId),
                (rs, i) -> rs.getObject("id", UUID.class)).stream().findFirst();
    }

    @Override
    public Optional<UUID> reversalOf(UUID chamaId, UUID entryId) {
        return reversalsOf(chamaId, List.of(entryId)).values().stream().findFirst();
    }

    @Override
    public Map<UUID, UUID> reversalsOf(UUID chamaId, Collection<UUID> entryIds) {
        Map<UUID, UUID> result = new HashMap<>();
        if (entryIds.isEmpty()) {
            return result;
        }
        jdbc.query("""
                        select reverses_entry_id, id from ledger.journal_entries
                         where tenant_id = :tenant and reverses_entry_id in (:ids)""",
                Map.of("tenant", chamaId, "ids", entryIds),
                rs -> {
                    result.put(rs.getObject("reverses_entry_id", UUID.class), rs.getObject("id", UUID.class));
                });
        return result;
    }

    @Override
    public Page<JournalEntry> entries(UUID chamaId, LocalDate from, LocalDate to, String sourceType, Pageable pageable) {
        StringBuilder where = new StringBuilder(" where tenant_id = :tenant");
        MapSqlParameterSource params = new MapSqlParameterSource("tenant", chamaId);
        if (from != null) {
            where.append(" and entry_date >= :from");
            params.addValue("from", Date.valueOf(from));
        }
        if (to != null) {
            where.append(" and entry_date <= :to");
            params.addValue("to", Date.valueOf(to));
        }
        if (sourceType != null && !sourceType.isBlank()) {
            where.append(" and source_type = :sourceType");
            params.addValue("sourceType", sourceType.trim());
        }
        Long total = jdbc.queryForObject("select count(*) from ledger.journal_entries" + where, params, Long.class);
        params.addValue("limit", pageable.getPageSize()).addValue("offset", pageable.getOffset());
        List<EntryRow> rows = jdbc.query(ENTRY_SELECT + where + " order by entry_number desc limit :limit offset :offset",
                params, ENTRY_ROW);
        return new PageImpl<>(withLines(chamaId, rows), pageable, total == null ? 0 : total);
    }

    // ---- balances ---------------------------------------------------------------------------

    @Override
    public Map<UUID, Totals> totalsByAccount(UUID chamaId, LocalDate asOf) {
        MapSqlParameterSource params = new MapSqlParameterSource("tenant", chamaId);
        String dateFilter = "";
        if (asOf != null) {
            dateFilter = " and e.entry_date <= :asOf";
            params.addValue("asOf", Date.valueOf(asOf));
        }
        Map<UUID, Totals> result = new HashMap<>();
        jdbc.query("""
                        select l.account_id, sum(l.debit_minor) as debits, sum(l.credit_minor) as credits
                          from ledger.journal_lines l
                          join ledger.journal_entries e on e.tenant_id = l.tenant_id and e.id = l.entry_id
                         where l.tenant_id = :tenant""" + dateFilter + " group by l.account_id",
                params,
                rs -> {
                    result.put(rs.getObject("account_id", UUID.class), new Totals(rs.getLong("debits"), rs.getLong("credits")));
                });
        return result;
    }

    @Override
    public Totals accountTotals(UUID chamaId, UUID accountId, UUID memberUserId, LocalDate before) {
        StringBuilder sql = new StringBuilder("""
                select coalesce(sum(l.debit_minor), 0) as debits, coalesce(sum(l.credit_minor), 0) as credits
                  from ledger.journal_lines l
                  join ledger.journal_entries e on e.tenant_id = l.tenant_id and e.id = l.entry_id
                 where l.tenant_id = :tenant and l.account_id = :account""");
        MapSqlParameterSource params = new MapSqlParameterSource("tenant", chamaId).addValue("account", accountId);
        if (memberUserId != null) {
            sql.append(" and l.member_user_id = :member");
            params.addValue("member", memberUserId);
        }
        if (before != null) {
            sql.append(" and e.entry_date < :before");
            params.addValue("before", Date.valueOf(before));
        }
        return jdbc.queryForObject(sql.toString(), params, (rs, i) -> new Totals(rs.getLong("debits"), rs.getLong("credits")));
    }

    @Override
    public List<StatementRow> statement(UUID chamaId, UUID accountId, UUID memberUserId, LocalDate from, LocalDate to) {
        StringBuilder sql = new StringBuilder("""
                select e.id, e.entry_number, e.entry_date, e.description, e.reference, e.source_type,
                       l.debit_minor, l.credit_minor, l.member_user_id, l.memo
                  from ledger.journal_lines l
                  join ledger.journal_entries e on e.tenant_id = l.tenant_id and e.id = l.entry_id
                 where l.tenant_id = :tenant and l.account_id = :account""");
        MapSqlParameterSource params = new MapSqlParameterSource("tenant", chamaId).addValue("account", accountId);
        if (memberUserId != null) {
            sql.append(" and l.member_user_id = :member");
            params.addValue("member", memberUserId);
        }
        if (from != null) {
            sql.append(" and e.entry_date >= :from");
            params.addValue("from", Date.valueOf(from));
        }
        if (to != null) {
            sql.append(" and e.entry_date <= :to");
            params.addValue("to", Date.valueOf(to));
        }
        sql.append(" order by e.entry_date, e.entry_number, l.line_number");
        return jdbc.query(sql.toString(), params, (rs, i) -> new StatementRow(
                rs.getObject("id", UUID.class), rs.getLong("entry_number"), rs.getDate("entry_date").toLocalDate(),
                rs.getString("description"), rs.getString("reference"), rs.getString("source_type"),
                rs.getLong("debit_minor"), rs.getLong("credit_minor"), rs.getObject("member_user_id", UUID.class),
                rs.getString("memo")));
    }

    // ---- mapping ------------------------------------------------------------------------------

    private static final RowMapper<LedgerAccount> ACCOUNT = (rs, i) -> new LedgerAccount(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("code"),
            rs.getString("name"),
            AccountType.valueOf(rs.getString("type")),
            rs.getString("system_key") == null ? null : LedgerAccountKey.valueOf(rs.getString("system_key")),
            Currency.getInstance(rs.getString("currency")),
            rs.getBoolean("member_ledger"),
            rs.getBoolean("active"),
            rs.getObject("created_by", UUID.class),
            rs.getTimestamp("created_at").toInstant());

    private static final String ENTRY_SELECT = """
            select id, tenant_id, entry_number, entry_date, description, reference, source_type, source_id,
                   reverses_entry_id, recorded_by, recorded_at
              from ledger.journal_entries""";

    /** An entry without its lines; lines are loaded for a whole page in one query. */
    private record EntryRow(UUID id, UUID chamaId, long number, LocalDate date, String description, String reference,
                            String sourceType, String sourceId, UUID reverses, UUID recordedBy, Timestamp recordedAt) {
    }

    private static final RowMapper<EntryRow> ENTRY_ROW = (ResultSet rs, int i) -> new EntryRow(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getLong("entry_number"),
            rs.getDate("entry_date").toLocalDate(),
            rs.getString("description"),
            rs.getString("reference"),
            rs.getString("source_type"),
            rs.getString("source_id"),
            rs.getObject("reverses_entry_id", UUID.class),
            rs.getObject("recorded_by", UUID.class),
            rs.getTimestamp("recorded_at"));

    private List<JournalEntry> withLines(UUID chamaId, List<EntryRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<JournalLine>> lines = new LinkedHashMap<>();
        rows.forEach(row -> lines.put(row.id(), new ArrayList<>()));
        jdbc.query("""
                        select entry_id, account_id, debit_minor, credit_minor, member_user_id, memo
                          from ledger.journal_lines
                         where tenant_id = :tenant and entry_id in (:ids)
                         order by entry_id, line_number""",
                Map.of("tenant", chamaId, "ids", lines.keySet()),
                rs -> {
                    lines.get(rs.getObject("entry_id", UUID.class)).add(line(rs));
                });
        return rows.stream().map(row -> new JournalEntry(row.id(), row.chamaId(), row.number(), row.date(),
                row.description(), row.reference(), row.sourceType(), row.sourceId(), row.reverses(), row.recordedBy(),
                row.recordedAt().toInstant(), lines.get(row.id()))).toList();
    }

    private static JournalLine line(ResultSet rs) throws SQLException {
        long debit = rs.getLong("debit_minor");
        long credit = rs.getLong("credit_minor");
        return new JournalLine(rs.getObject("account_id", UUID.class),
                debit > 0 ? EntrySide.DEBIT : EntrySide.CREDIT, debit > 0 ? debit : credit,
                rs.getObject("member_user_id", UUID.class), rs.getString("memo"));
    }
}
