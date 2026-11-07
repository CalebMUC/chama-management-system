/**
 * What other modules may use: {@link ke.chama.modules.expense.api.ExpenseTotals} to read spending,
 * and {@link ke.chama.modules.expense.api.InvestmentLookup}, which the investment module implements
 * so expenses can be charged to an investment.
 */
@NamedInterface("api")
package ke.chama.modules.expense.api;

import org.springframework.modulith.NamedInterface;
