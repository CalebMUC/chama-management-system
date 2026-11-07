package ke.chama.modules.account.api;

import java.util.List;
import java.util.UUID;

/** The chama's savings products, for other modules. */
public interface SavingsProducts {

    /** Every product, active or not, by allocation priority then code. */
    List<SavingsProductSummary> products(UUID chamaId);

    /** The product that takes what a payment owes nowhere else ("Deposits" unless renamed). */
    SavingsProductSummary defaultProduct(UUID chamaId);
}
