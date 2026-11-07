package ke.chama.modules.loan.application.port.out;

import ke.chama.modules.loan.domain.model.LoanProduct;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Row-level security limits every query to the current chama. */
public interface LoanProductRepository {

    void insert(LoanProduct product);

    /** False if the product changed since it was read (optimistic lock). */
    boolean update(LoanProduct product);

    /** Active first, then by code. */
    List<LoanProduct> products(UUID chamaId);

    Optional<LoanProduct> product(UUID chamaId, UUID productId);

    Optional<LoanProduct> productByCode(UUID chamaId, String code);

    Optional<LoanProduct> defaultProduct(UUID chamaId);
}
