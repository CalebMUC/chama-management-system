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
    PENALTY_WAIVE,

    EXPENSE_VIEW,
    EXPENSE_CREATE,
    EXPENSE_APPROVE,

    INVESTMENT_VIEW,
    INVESTMENT_MANAGE,

    LEDGER_VIEW,
    /** Post manual journal entries (opening balances, bank charges) and reverse entries. */
    LEDGER_POST,
    /** Add accounts to the chart of accounts and deactivate them. */
    LEDGER_ACCOUNTS_MANAGE,
    REPORT_VIEW_ALL,
    AUDIT_LOG_VIEW
}
