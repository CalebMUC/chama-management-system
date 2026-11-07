package ke.chama.modules.transaction.api;

/**
 * What other modules may know about an account in the chart, e.g. to check that an expense is
 * charged to an expense account.
 *
 * @param type         ASSET, LIABILITY, EQUITY, INCOME or EXPENSE
 * @param memberLedger every line names a member, giving each member their own balance
 */
public record LedgerAccountSummary(String code, String name, String type, boolean active, boolean memberLedger) {

    public static final String EXPENSE = "EXPENSE";

    public boolean isExpense() {
        return EXPENSE.equals(type);
    }
}
