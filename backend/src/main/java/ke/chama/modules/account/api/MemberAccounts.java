package ke.chama.modules.account.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Members' accounts in the chama's savings products, for other modules. */
public interface MemberAccounts {

    /**
     * Opens the accounts every seated member must have (one per active mandatory product).
     * Idempotent and cheap; runs in the caller's transaction, which must exist.
     */
    void openMandatoryAccounts(UUID chamaId);

    /** Every account in the chama, open or closed. */
    List<MemberAccountSummary> accounts(UUID chamaId);

    List<MemberAccountSummary> accountsOf(UUID chamaId, UUID memberUserId);

    /**
     * The member's account in the product, opened on {@code openedOn} if they have none yet (e.g.
     * for balances brought over from spreadsheets). Runs in the caller's transaction.
     */
    MemberAccountSummary ensureAccount(UUID chamaId, UUID memberUserId, UUID productId, LocalDate openedOn,
                                       UUID actorUserId);

    /** The member's combined balance in the products that count toward their loan limit. */
    long loanBackingSavingsMinor(UUID chamaId, UUID memberUserId);

    /**
     * Moves the day an account was opened (history brought over from spreadsheets). Runs in the
     * caller's transaction; audit-logged as MEMBER_ACCOUNT_OPENED_ON_CORRECTED.
     *
     * @return the opened date before the change
     */
    LocalDate correctOpenedOn(UUID chamaId, UUID accountId, LocalDate openedOn, UUID actorUserId);
}
