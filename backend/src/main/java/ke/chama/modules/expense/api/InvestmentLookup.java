package ke.chama.modules.expense.api;

import java.util.Optional;
import java.util.UUID;

/**
 * How the expense module finds the investment an expense is charged to (e.g. an Airbnb's rent and
 * bills). The investment module implements it, so expenses never depend on investments and the two
 * modules have no dependency cycle.
 */
public interface InvestmentLookup {

    Optional<LinkedInvestment> find(UUID chamaId, UUID investmentId);

    /** @param active only active (funded, not closed) investments can have expenses charged to them */
    record LinkedInvestment(UUID id, String name, boolean active) {
    }
}
