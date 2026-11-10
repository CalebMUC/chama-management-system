package ke.chama.shared.security;

/**
 * Everything a member can be allowed to do inside one chama. Endpoints check permissions,
 * never role names, so a chama can reshape its roles without code changes.
 * <p>
 * Viewing your <em>own</em> records (your contributions, your loans) needs no permission:
 * being an active member is enough. The {@code *_VIEW_ALL} permissions cover everyone's records.
 * <p>
 * Stored by name in {@code member.role_permissions}: only add values; never rename or remove one
 * without a data migration.
 */
public enum ChamaPermission {
    CHAMA_VIEW,
    CHAMA_SETTINGS_MANAGE,

    MEMBER_VIEW,
    MEMBER_INVITE,
    MEMBER_APPROVE,
    MEMBER_SUSPEND,
    MEMBER_ROLES_MANAGE,
    ROLE_PERMISSIONS_MANAGE,

    CONTRIBUTION_VIEW_ALL,
    CONTRIBUTION_RECORD,
    CONTRIBUTION_REVERSE,

    LOAN_APPLY,
    LOAN_VIEW_ALL,
    LOAN_APPROVE,
    LOAN_DISBURSE,
    LOAN_REPAYMENT_RECORD,

    PENALTY_VIEW_ALL,
    /** Impose fines and record penalty payments. */
    PENALTY_RECORD,
    PENALTY_WAIVE,

    EXPENSE_VIEW,
    EXPENSE_CREATE,
    EXPENSE_APPROVE,

    INVESTMENT_VIEW,
    /** Propose investments, fund them, and record income, withdrawals, valuations and closure. */
    INVESTMENT_MANAGE,
    /** Approve or reject proposed investments (never your own proposal). */
    INVESTMENT_APPROVE,

    LEDGER_VIEW,
    /** Post manual journal entries (opening balances, bank charges) and reverse entries. */
    LEDGER_POST,
    /** Add accounts to the chart of accounts and deactivate them. */
    LEDGER_ACCOUNTS_MANAGE,
    REPORT_VIEW_ALL,
    AUDIT_LOG_VIEW,

    /** Add savings and loan products, change their terms, and switch them on or off. */
    PRODUCTS_MANAGE,
    /** See every member's accounts, balances and statements. */
    MEMBER_ACCOUNTS_VIEW_ALL,
    /** Open, close and reopen members' accounts in savings products. */
    MEMBER_ACCOUNTS_MANAGE,

    /**
     * Report a payment you made yourself (a contribution, a penalty payment or a loan repayment)
     * for an official to check and record. Reviewing a report needs the matching record
     * permission: CONTRIBUTION_RECORD, PENALTY_RECORD or LOAN_REPAYMENT_RECORD.
     */
    PAYMENT_SUBMIT
}
