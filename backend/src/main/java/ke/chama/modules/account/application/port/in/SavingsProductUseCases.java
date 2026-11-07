package ke.chama.modules.account.application.port.in;

import ke.chama.modules.account.application.port.in.AccountViews.ProductView;
import ke.chama.modules.account.domain.model.SavingsProduct;
import ke.chama.modules.account.domain.model.SavingsProductKind;

import java.util.List;
import java.util.UUID;

/** The chama's savings products. Changes are audit-logged. */
public interface SavingsProductUseCases {

    /** Active products first, by allocation priority. */
    List<ProductView> listProducts(UUID chamaId);

    ProductView product(UUID chamaId, UUID productId);

    /**
     * Adds a product with its own member-ledger account. A mandatory product opens an account for
     * every seated member at once.
     *
     * @param ledgerAccountCode optional; the next free code for the kind when null (2010, 2020, ... or 3100 for shares)
     */
    ProductView create(UUID chamaId, UUID actorUserId, String code, SavingsProductKind kind, String ledgerAccountCode,
                       SavingsProduct.Terms terms);

    /** New terms, for contribution periods opened from now on. */
    ProductView update(UUID chamaId, UUID actorUserId, UUID productId, SavingsProduct.Terms terms);

    ProductView setActive(UUID chamaId, UUID actorUserId, UUID productId, boolean active);
}
