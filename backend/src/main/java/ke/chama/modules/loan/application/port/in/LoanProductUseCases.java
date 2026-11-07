package ke.chama.modules.loan.application.port.in;

import ke.chama.modules.loan.domain.model.LoanProduct;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The chama's loan products. Changes are audit-logged and apply to loans applied for afterwards. */
public interface LoanProductUseCases {

    /** Active first. */
    List<LoanProductView> products(UUID chamaId);

    LoanProductView product(UUID chamaId, UUID productId);

    LoanProductView create(UUID chamaId, UUID actorUserId, String code, LoanProduct.Terms terms);

    LoanProductView update(UUID chamaId, UUID actorUserId, UUID productId, LoanProduct.Terms terms);

    LoanProductView setActive(UUID chamaId, UUID actorUserId, UUID productId, boolean active);

    /**
     * @param interestBasisPoints flat interest on the principal for the whole term (1000 = 10%)
     * @param maxMultiplier       a loan may be at most this many times the member's loan-backing savings
     */
    record LoanProductView(UUID id,
                           String code,
                           String name,
                           String currency,
                           int interestBasisPoints,
                           int maxTermMonths,
                           int maxMultiplier,
                           int minMembershipMonths,
                           long guarantorThresholdMinor,
                           int guarantorsRequired,
                           boolean defaultProduct,
                           boolean active,
                           Instant createdAt,
                           Instant updatedAt) {
    }
}
