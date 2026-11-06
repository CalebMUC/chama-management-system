package ke.chama.modules.chama.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Read-only chama lookup for other modules. Only the current tenant's chama is visible, so
 * {@code chamaId} is normally {@code TenantContext.requireChama()}.
 */
public interface ChamaDirectory {

    Optional<ChamaSummary> find(UUID chamaId);

    /** The chama's licence; empty only for a chama created before licensing. */
    Optional<LicenseSummary> license(UUID chamaId);

    /** The chama's current contribution rules (amount, frequency, due day, grace, late penalty). */
    Optional<ContributionRules> contributionRules(UUID chamaId);

    /** The chama's current loan policy (limit, interest, guarantors, approval quorum). */
    Optional<LoanRules> loanRules(UUID chamaId);
}
