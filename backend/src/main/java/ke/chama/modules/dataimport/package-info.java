/**
 * Bringing a chama's existing records over from spreadsheets: an .xlsx template, a preview that
 * checks every row, and a one-time import of opening balances (members, savings, loans, penalties,
 * cash and investments) as at a cut-over date.
 */
@ApplicationModule(id = "dataimport", displayName = "Data import")
package ke.chama.modules.dataimport;

import org.springframework.modulith.ApplicationModule;
