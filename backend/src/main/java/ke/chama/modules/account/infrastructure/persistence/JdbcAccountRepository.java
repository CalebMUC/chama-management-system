package ke.chama.modules.account.infrastructure.persistence;

import ke.chama.modules.account.application.port.out.AccountRepository;
import ke.chama.modules.account.domain.model.MemberAccount;
import ke.chama.modules.account.domain.model.SavingsProduct;
import ke.chama.modules.account.domain.model.SavingsProductKind;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Plain JDBC, like the ledger. Row-level security restricts every statement to the current chama. */
@Repository
class JdbcAccountRepository implements AccountRepository {

    private static final String PRODUCT_COLUMNS = """
            id, tenant_id, code, name, kind, ledger_account_code, contribution_minor, allocation_priority, mandatory,
            counts_toward_loan_limit, refundable_on_exit, earns_dividend, is_default, active, created_by, created_at,
            updated_at, version""";

    private static final String ACCOUNT_COLUMNS = """
            id, tenant_id, account_number, member_user_id, product_id, status, opened_on, opened_by, closed_on,
            closed_by, closure_reason, created_at, version""";

    private final NamedParameterJdbcTemplate jdbc;

    JdbcAccountRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- savings products -----------------------------------------------------------------------

    @Override
    public void insertProduct(SavingsProduct p) {
        jdbc.update("""
                insert into account.savings_products (id, tenant_id, code, name, kind, ledger_account_code,
                                                      contribution_minor, allocation_priority, mandatory,
                                                      counts_toward_loan_limit, refundable_on_exit, earns_dividend,
                                                      is_default, active, created_by, created_at, updated_at)
                values (:id, :tenant, :code, :name, :kind, :ledgerCode, :contribution, :priority, :mandatory,
                        :loanBacking, :refundable, :dividend, :isDefault, :active, :createdBy, :createdAt, :updatedAt)""",
                productParams(p)
                        .addValue("tenant", p.chamaId())
                        .addValue("code", p.code())
                        .addValue("kind", p.kind().name())
                        .addValue("ledgerCode", p.ledgerAccountCode())
                        .addValue("isDefault", p.defaultProduct())
                        .addValue("createdBy", p.createdBy())
                        .addValue("createdAt", Timestamp.from(p.createdAt())));
    }

    @Override
    public boolean updateProduct(SavingsProduct p) {
        int updated = jdbc.update("""
                update account.savings_products
                   set name = :name, contribution_minor = :contribution, allocation_priority = :priority,
                       mandatory = :mandatory, counts_toward_loan_limit = :loanBacking,
                       refundable_on_exit = :refundable, earns_dividend = :dividend, active = :active,
                       updated_at = :updatedAt, version = version + 1
                 where tenant_id = :tenant and id = :id and version = :version""",
                productParams(p).addValue("tenant", p.chamaId()).addValue("version", p.version()));
        return updated == 1;
    }

    private static MapSqlParameterSource productParams(SavingsProduct p) {
        return new MapSqlParameterSource()
                .addValue("id", p.id())
                .addValue("name", p.name())
                .addValue("contribution", p.contributionMinor())
                .addValue("priority", p.allocationPriority())
                .addValue("mandatory", p.mandatory())
                .addValue("loanBacking", p.countsTowardLoanLimit())
                .addValue("refundable", p.refundableOnExit())
                .addValue("dividend", p.earnsDividend())
                .addValue("active", p.active())
                .addValue("updatedAt", Timestamp.from(p.updatedAt()));
    }

    @Override
    public List<SavingsProduct> products(UUID chamaId) {
        return jdbc.query("select " + PRODUCT_COLUMNS + " from account.savings_products where tenant_id = :tenant "
                + "order by allocation_priority, code", Map.of("tenant", chamaId), PRODUCT);
    }

    @Override
    public Optional<SavingsProduct> product(UUID chamaId, UUID productId) {
        return jdbc.query("select " + PRODUCT_COLUMNS + " from account.savings_products where tenant_id = :tenant and id = :id",
                Map.of("tenant", chamaId, "id", productId), PRODUCT).stream().findFirst();
    }

    @Override
    public Optional<SavingsProduct> productByCode(UUID chamaId, String code) {
        return jdbc.query("select " + PRODUCT_COLUMNS + " from account.savings_products where tenant_id = :tenant and code = :code",
                Map.of("tenant", chamaId, "code", code), PRODUCT).stream().findFirst();
    }

    @Override
    public Optional<SavingsProduct> defaultProduct(UUID chamaId) {
        return jdbc.query("select " + PRODUCT_COLUMNS + " from account.savings_products where tenant_id = :tenant and is_default",
                Map.of("tenant", chamaId), PRODUCT).stream().findFirst();
    }

    @Override
    public boolean ledgerCodeUsed(UUID chamaId, String ledgerAccountCode) {
        Boolean used = jdbc.queryForObject("""
                        select exists (select 1 from account.savings_products
                                        where tenant_id = :tenant and ledger_account_code = :code)""",
                Map.of("tenant", chamaId, "code", ledgerAccountCode), Boolean.class);
        return Boolean.TRUE.equals(used);
    }

    // ---- member accounts ------------------------------------------------------------------------

    @Override
    public void insertAccounts(Collection<MemberAccount> accounts) {
        if (accounts.isEmpty()) {
            return;
        }
        MapSqlParameterSource[] rows = accounts.stream().map(a -> new MapSqlParameterSource()
                .addValue("id", a.id())
                .addValue("tenant", a.chamaId())
                .addValue("number", a.accountNumber())
                .addValue("member", a.memberUserId())
                .addValue("product", a.productId())
                .addValue("status", a.status().name())
                .addValue("openedOn", Date.valueOf(a.openedOn()))
                .addValue("openedBy", a.openedBy())
                .addValue("createdAt", Timestamp.from(a.createdAt()))).toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate("""
                insert into account.member_accounts (id, tenant_id, account_number, member_user_id, product_id, status,
                                                     opened_on, opened_by, created_at)
                values (:id, :tenant, :number, :member, :product, :status, :openedOn, :openedBy, :createdAt)
                on conflict (tenant_id, product_id, member_user_id) do nothing""", rows);
    }

    @Override
    public boolean updateAccount(MemberAccount a) {
        int updated = jdbc.update("""
                update account.member_accounts
                   set status = :status, opened_on = :openedOn, opened_by = :openedBy, closed_on = :closedOn,
                       closed_by = :closedBy, closure_reason = :reason, version = version + 1
                 where tenant_id = :tenant and id = :id and version = :version""",
                new MapSqlParameterSource()
                        .addValue("status", a.status().name())
                        .addValue("openedOn", Date.valueOf(a.openedOn()))
                        .addValue("openedBy", a.openedBy())
                        .addValue("closedOn", a.closedOn() == null ? null : Date.valueOf(a.closedOn()))
                        .addValue("closedBy", a.closedBy())
                        .addValue("reason", a.closureReason())
                        .addValue("tenant", a.chamaId())
                        .addValue("id", a.id())
                        .addValue("version", a.version()));
        return updated == 1;
    }

    @Override
    public List<MemberAccount> accounts(UUID chamaId) {
        return jdbc.query("select " + ACCOUNT_COLUMNS + " from account.member_accounts where tenant_id = :tenant "
                + "order by account_number", Map.of("tenant", chamaId), ACCOUNT);
    }

    @Override
    public List<MemberAccount> accountsOf(UUID chamaId, UUID memberUserId) {
        return jdbc.query("select " + ACCOUNT_COLUMNS + " from account.member_accounts "
                        + "where tenant_id = :tenant and member_user_id = :member order by account_number",
                Map.of("tenant", chamaId, "member", memberUserId), ACCOUNT);
    }

    @Override
    public Optional<MemberAccount> account(UUID chamaId, UUID accountId) {
        return jdbc.query("select " + ACCOUNT_COLUMNS + " from account.member_accounts where tenant_id = :tenant and id = :id",
                Map.of("tenant", chamaId, "id", accountId), ACCOUNT).stream().findFirst();
    }

    @Override
    public Optional<MemberAccount> accountFor(UUID chamaId, UUID memberUserId, UUID productId) {
        return jdbc.query("select " + ACCOUNT_COLUMNS + " from account.member_accounts "
                        + "where tenant_id = :tenant and member_user_id = :member and product_id = :product",
                Map.of("tenant", chamaId, "member", memberUserId, "product", productId), ACCOUNT).stream().findFirst();
    }

    // ---- mapping ------------------------------------------------------------------------------

    private static final RowMapper<SavingsProduct> PRODUCT = (rs, i) -> new SavingsProduct(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("code"),
            rs.getString("name"),
            SavingsProductKind.valueOf(rs.getString("kind")),
            rs.getString("ledger_account_code"),
            rs.getLong("contribution_minor"),
            rs.getInt("allocation_priority"),
            rs.getBoolean("mandatory"),
            rs.getBoolean("counts_toward_loan_limit"),
            rs.getBoolean("refundable_on_exit"),
            rs.getBoolean("earns_dividend"),
            rs.getBoolean("is_default"),
            rs.getBoolean("active"),
            rs.getObject("created_by", UUID.class),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            rs.getLong("version"));

    private static final RowMapper<MemberAccount> ACCOUNT = (rs, i) -> new MemberAccount(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("account_number"),
            rs.getObject("member_user_id", UUID.class),
            rs.getObject("product_id", UUID.class),
            MemberAccount.Status.valueOf(rs.getString("status")),
            rs.getDate("opened_on").toLocalDate(),
            rs.getObject("opened_by", UUID.class),
            rs.getDate("closed_on") == null ? null : rs.getDate("closed_on").toLocalDate(),
            rs.getObject("closed_by", UUID.class),
            rs.getString("closure_reason"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getLong("version"));
}
