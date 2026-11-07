/**
 * Read-only reporting and dashboards: financial statements, cash book, contribution report, loan
 * portfolio (aging, PAR30), savings register, member statements, and the chama and member
 * dashboards. Owns no tables: every figure comes from the ledger and the other modules' read APIs.
 */
@ApplicationModule(id = "report", displayName = "Report")
package ke.chama.modules.report;

import org.springframework.modulith.ApplicationModule;
