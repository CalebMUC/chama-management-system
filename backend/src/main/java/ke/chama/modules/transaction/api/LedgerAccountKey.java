package ke.chama.modules.transaction.api;

/**
 * The accounts every chama has, so modules can post without knowing account codes or ids.
 * A chama may add more accounts of its own; those are referenced by code.
 */
public enum LedgerAccountKey {
    // Assets
    CASH,
    BANK,
    MOBILE_MONEY,
    /** Per member: what each member owes on loans. */
    LOANS_RECEIVABLE,
    INVESTMENTS,
    // Liabilities
    /** Per member: what the chama owes each member (their savings). */
    MEMBER_SAVINGS,
    // Equity
    RETAINED_SURPLUS,
    // Income
    INTEREST_INCOME,
    PENALTY_INCOME,
    INVESTMENT_INCOME,
    OTHER_INCOME,
    // Expenses
    OPERATING_EXPENSES,
    BANK_CHARGES
}
