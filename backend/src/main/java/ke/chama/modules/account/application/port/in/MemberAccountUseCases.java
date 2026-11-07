package ke.chama.modules.account.application.port.in;

import ke.chama.modules.account.application.port.in.AccountViews.AccountView;
import ke.chama.modules.account.application.port.in.AccountViews.MyAccountsView;
import ke.chama.modules.account.application.port.in.AccountViews.StatementView;
import ke.chama.modules.account.domain.model.MemberAccount;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Members' accounts in the savings products, with balances from the ledger. Accounts in mandatory
 * products open by themselves; officials open accounts in the others. Changes are audit-logged.
 */
public interface MemberAccountUseCases {

    /** Every filter is optional. By account number. */
    List<AccountView> accounts(UUID chamaId, UUID memberUserId, UUID productId, MemberAccount.Status status);

    AccountView account(UUID chamaId, UUID accountId);

    StatementView statement(UUID chamaId, UUID accountId, LocalDate from, LocalDate to);

    MyAccountsView mine(UUID chamaId, UUID memberUserId);

    /** The caller's own account's statement; not found unless the account is theirs. */
    StatementView myStatement(UUID chamaId, UUID memberUserId, UUID accountId, LocalDate from, LocalDate to);

    AccountView open(UUID chamaId, UUID actorUserId, UUID memberUserId, UUID productId);

    /** Only with a zero balance, and never a mandatory product's account of a seated member. */
    AccountView close(UUID chamaId, UUID actorUserId, UUID accountId, String reason);

    AccountView reopen(UUID chamaId, UUID actorUserId, UUID accountId);
}
