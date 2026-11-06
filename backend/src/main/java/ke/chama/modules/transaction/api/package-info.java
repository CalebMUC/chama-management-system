/**
 * What other modules may use: {@link ke.chama.modules.transaction.api.Ledger} to post entries
 * and read balances. Contributions, loans, penalties, expenses and investments never write
 * balances of their own; they post to the ledger.
 */
@NamedInterface("api")
package ke.chama.modules.transaction.api;

import org.springframework.modulith.NamedInterface;
