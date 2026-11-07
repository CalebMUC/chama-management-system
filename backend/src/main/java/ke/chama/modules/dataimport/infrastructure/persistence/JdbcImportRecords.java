package ke.chama.modules.dataimport.infrastructure.persistence;

import ke.chama.modules.dataimport.application.port.in.OpeningBalanceImports.ImportView;
import ke.chama.modules.dataimport.application.port.in.OpeningBalanceImports.SummaryView;
import ke.chama.modules.dataimport.application.port.out.ImportRecords;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Plain JDBC. Row-level security restricts every statement to the current chama. */
@Repository
class JdbcImportRecords implements ImportRecords {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcImportRecords(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ImportView> find(UUID chamaId) {
        return jdbc.query("""
                        select id, as_of, file_name, imported_by, imported_at, ledger_entry_id, currency, new_members,
                               existing_members, savings_minor, loans, loans_minor, penalties_minor, cash_minor, bank_minor,
                               mobile_money_minor, investments, investments_minor, retained_surplus_minor
                          from dataimport.opening_balance_imports where tenant_id = :tenant""",
                Map.of("tenant", chamaId),
                (rs, i) -> new ImportView(rs.getObject("id", UUID.class), rs.getDate("as_of").toLocalDate(),
                        rs.getString("file_name"), rs.getObject("imported_by", UUID.class),
                        rs.getTimestamp("imported_at").toInstant(), rs.getObject("ledger_entry_id", UUID.class),
                        new SummaryView(rs.getString("currency"), rs.getInt("new_members"), rs.getInt("existing_members"),
                                rs.getLong("savings_minor"), rs.getInt("loans"), rs.getLong("loans_minor"),
                                rs.getLong("penalties_minor"), rs.getLong("cash_minor"), rs.getLong("bank_minor"),
                                rs.getLong("mobile_money_minor"), rs.getInt("investments"), rs.getLong("investments_minor"),
                                rs.getLong("retained_surplus_minor")),
                        List.of()))
                .stream().findFirst();
    }

    @Override
    public void insert(UUID chamaId, ImportView r) {
        SummaryView s = r.summary();
        jdbc.update("""
                insert into dataimport.opening_balance_imports
                       (id, tenant_id, as_of, file_name, imported_by, imported_at, ledger_entry_id, currency, new_members,
                        existing_members, savings_minor, loans, loans_minor, penalties_minor, cash_minor, bank_minor,
                        mobile_money_minor, investments, investments_minor, retained_surplus_minor)
                values (:id, :tenant, :asOf, :fileName, :importedBy, :importedAt, :entry, :currency, :newMembers,
                        :existingMembers, :savings, :loans, :loansMinor, :penalties, :cash, :bank, :mobileMoney,
                        :investments, :investmentsMinor, :surplus)""",
                new MapSqlParameterSource()
                        .addValue("id", r.id())
                        .addValue("tenant", chamaId)
                        .addValue("asOf", Date.valueOf(r.asOf()))
                        .addValue("fileName", r.fileName())
                        .addValue("importedBy", r.importedBy())
                        .addValue("importedAt", Timestamp.from(r.importedAt()))
                        .addValue("entry", r.ledgerEntryId())
                        .addValue("currency", s.currency())
                        .addValue("newMembers", s.newMembers())
                        .addValue("existingMembers", s.existingMembers())
                        .addValue("savings", s.savingsMinor())
                        .addValue("loans", s.loans())
                        .addValue("loansMinor", s.loansOutstandingMinor())
                        .addValue("penalties", s.penaltiesMinor())
                        .addValue("cash", s.cashMinor())
                        .addValue("bank", s.bankMinor())
                        .addValue("mobileMoney", s.mobileMoneyMinor())
                        .addValue("investments", s.investments())
                        .addValue("investmentsMinor", s.investmentsMinor())
                        .addValue("surplus", s.retainedSurplusMinor()));
    }
}
