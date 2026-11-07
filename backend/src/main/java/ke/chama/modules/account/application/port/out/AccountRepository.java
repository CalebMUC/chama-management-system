package ke.chama.modules.account.application.port.out;

import ke.chama.modules.account.domain.model.MemberAccount;
import ke.chama.modules.account.domain.model.SavingsProduct;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Row-level security limits every query to the current chama; {@code chamaId} is still passed. */
public interface AccountRepository {

    // ---- savings products -----------------------------------------------------------------------

    void insertProduct(SavingsProduct product);

    /** False if the product changed since it was read (optimistic lock). */
    boolean updateProduct(SavingsProduct product);

    /** By allocation priority, then code. */
    List<SavingsProduct> products(UUID chamaId);

    Optional<SavingsProduct> product(UUID chamaId, UUID productId);

    Optional<SavingsProduct> productByCode(UUID chamaId, String code);

    Optional<SavingsProduct> defaultProduct(UUID chamaId);

    boolean ledgerCodeUsed(UUID chamaId, String ledgerAccountCode);

    // ---- member accounts ------------------------------------------------------------------------

    /** Accounts that already exist for the same member and product are skipped. */
    void insertAccounts(Collection<MemberAccount> accounts);

    /** False if the account changed since it was read (optimistic lock). */
    boolean updateAccount(MemberAccount account);

    /** By account number. */
    List<MemberAccount> accounts(UUID chamaId);

    List<MemberAccount> accountsOf(UUID chamaId, UUID memberUserId);

    Optional<MemberAccount> account(UUID chamaId, UUID accountId);

    Optional<MemberAccount> accountFor(UUID chamaId, UUID memberUserId, UUID productId);
}
