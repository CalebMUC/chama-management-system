package ke.chama.modules.loan.infrastructure.persistence;

import ke.chama.modules.loan.application.port.out.LoanProductRepository;
import ke.chama.modules.loan.domain.model.LoanProduct;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcLoanProductRepository implements LoanProductRepository {

    private static final String COLUMNS = """
            id, tenant_id, code, name, interest_bp, max_term_months, max_multiplier, min_membership_months,
            guarantor_threshold_minor, guarantors_required, is_default, active, created_by, created_at, updated_at,
            version""";

    private final NamedParameterJdbcTemplate jdbc;

    JdbcLoanProductRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(LoanProduct p) {
        jdbc.update("""
                insert into loan.products (id, tenant_id, code, name, interest_bp, max_term_months, max_multiplier,
                                           min_membership_months, guarantor_threshold_minor, guarantors_required,
                                           is_default, active, created_by, created_at, updated_at)
                values (:id, :tenant, :code, :name, :interestBp, :maxTerm, :multiplier, :minMonths, :threshold,
                        :guarantors, :isDefault, :active, :createdBy, :createdAt, :updatedAt)""",
                params(p).addValue("code", p.code()).addValue("isDefault", p.defaultProduct())
                        .addValue("createdBy", p.createdBy()).addValue("createdAt", Timestamp.from(p.createdAt())));
    }

    @Override
    public boolean update(LoanProduct p) {
        return jdbc.update("""
                update loan.products
                   set name = :name, interest_bp = :interestBp, max_term_months = :maxTerm,
                       max_multiplier = :multiplier, min_membership_months = :minMonths,
                       guarantor_threshold_minor = :threshold, guarantors_required = :guarantors, active = :active,
                       updated_at = :updatedAt, version = version + 1
                 where tenant_id = :tenant and id = :id and version = :version""",
                params(p).addValue("version", p.version())) == 1;
    }

    private static MapSqlParameterSource params(LoanProduct p) {
        return new MapSqlParameterSource()
                .addValue("id", p.id())
                .addValue("tenant", p.chamaId())
                .addValue("name", p.name())
                .addValue("interestBp", p.interestBasisPoints())
                .addValue("maxTerm", p.maxTermMonths())
                .addValue("multiplier", p.maxMultiplier())
                .addValue("minMonths", p.minMembershipMonths())
                .addValue("threshold", p.guarantorThresholdMinor())
                .addValue("guarantors", p.guarantorsRequired())
                .addValue("active", p.active())
                .addValue("updatedAt", Timestamp.from(p.updatedAt()));
    }

    @Override
    public List<LoanProduct> products(UUID chamaId) {
        return jdbc.query("select " + COLUMNS + " from loan.products where tenant_id = :tenant "
                + "order by active desc, is_default desc, code", Map.of("tenant", chamaId), PRODUCT);
    }

    @Override
    public Optional<LoanProduct> product(UUID chamaId, UUID productId) {
        return jdbc.query("select " + COLUMNS + " from loan.products where tenant_id = :tenant and id = :id",
                Map.of("tenant", chamaId, "id", productId), PRODUCT).stream().findFirst();
    }

    @Override
    public Optional<LoanProduct> productByCode(UUID chamaId, String code) {
        return jdbc.query("select " + COLUMNS + " from loan.products where tenant_id = :tenant and code = :code",
                Map.of("tenant", chamaId, "code", code), PRODUCT).stream().findFirst();
    }

    @Override
    public Optional<LoanProduct> defaultProduct(UUID chamaId) {
        return jdbc.query("select " + COLUMNS + " from loan.products where tenant_id = :tenant and is_default",
                Map.of("tenant", chamaId), PRODUCT).stream().findFirst();
    }

    private static final RowMapper<LoanProduct> PRODUCT = (rs, i) -> new LoanProduct(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("code"),
            rs.getString("name"),
            rs.getInt("interest_bp"),
            rs.getInt("max_term_months"),
            rs.getInt("max_multiplier"),
            rs.getInt("min_membership_months"),
            rs.getLong("guarantor_threshold_minor"),
            rs.getInt("guarantors_required"),
            rs.getBoolean("is_default"),
            rs.getBoolean("active"),
            rs.getObject("created_by", UUID.class),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            rs.getLong("version"));
}
