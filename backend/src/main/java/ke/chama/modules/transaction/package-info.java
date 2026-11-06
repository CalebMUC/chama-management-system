/**
 * Double-entry ledger, the source of truth for every balance. Append-only: corrections are reversal entries.
 */
@ApplicationModule(id = "transaction", displayName = "Ledger")
package ke.chama.modules.transaction;

import org.springframework.modulith.ApplicationModule;
