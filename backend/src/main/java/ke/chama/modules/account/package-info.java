/**
 * Savings products (deposits, share capital, welfare, ...) and members' accounts in them. Each
 * product keeps its balances on its own member-ledger account, so balances always come from the
 * ledger; this module holds the product rules and which member has which account.
 */
@ApplicationModule(id = "account", displayName = "Member accounts")
package ke.chama.modules.account;

import org.springframework.modulith.ApplicationModule;
