package ke.chama.modules.member.domain.model;

import ke.chama.shared.security.ChamaPermission;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static ke.chama.shared.security.ChamaPermission.*;

/**
 * The permissions every new chama starts with. The chairperson can change them per chama
 * afterwards, within the limits of {@link #LOCKED_FOR_CHAIRPERSON}.
 * <p>
 * Built for separation of duties: the treasurer records money and creates expenses; the
 * chairperson and secretary approve expenses and investments (two approvers, for the default
 * quorum of two); the secretary handles membership; the auditor sees everything and changes nothing;
 * the admin runs operations (members, roles, settings, products) without touching money.
 */
public final class DefaultRolePermissions {

    /**
     * The chairperson role can never lose these. Otherwise a chama could remove every way to
     * fix roles and permissions, leaving nobody able to manage it.
     */
    public static final Set<ChamaPermission> LOCKED_FOR_CHAIRPERSON =
            Collections.unmodifiableSet(EnumSet.of(MEMBER_ROLES_MANAGE, ROLE_PERMISSIONS_MANAGE));

    /**
     * A plain member sees their own records, the chama's expenses and investments, applies for
     * loans and reports their own payments. They do not see the member directory or the
     * role-permission matrix (MEMBER_VIEW): those are for officials.
     */
    private static final Set<ChamaPermission> EVERY_MEMBER = EnumSet.of(
            CHAMA_VIEW, LOAN_APPLY, EXPENSE_VIEW, INVESTMENT_VIEW, PAYMENT_SUBMIT);

    private static final Map<ChamaRole, Set<ChamaPermission>> DEFAULTS = new EnumMap<>(ChamaRole.class);

    static {
        DEFAULTS.put(ChamaRole.MEMBER, EVERY_MEMBER);

        DEFAULTS.put(ChamaRole.TREASURER, union(EVERY_MEMBER, EnumSet.of(
                MEMBER_VIEW, CONTRIBUTION_VIEW_ALL, CONTRIBUTION_RECORD, CONTRIBUTION_REVERSE,
                LOAN_VIEW_ALL, LOAN_APPROVE, LOAN_DISBURSE, LOAN_REPAYMENT_RECORD,
                PENALTY_VIEW_ALL, PENALTY_RECORD, EXPENSE_CREATE, INVESTMENT_MANAGE, LEDGER_VIEW, LEDGER_POST, LEDGER_ACCOUNTS_MANAGE,
                REPORT_VIEW_ALL, PRODUCTS_MANAGE, MEMBER_ACCOUNTS_VIEW_ALL, MEMBER_ACCOUNTS_MANAGE)));

        DEFAULTS.put(ChamaRole.SECRETARY, union(EVERY_MEMBER, EnumSet.of(
                MEMBER_VIEW, MEMBER_INVITE, CONTRIBUTION_VIEW_ALL, LOAN_VIEW_ALL, PENALTY_VIEW_ALL, EXPENSE_APPROVE, INVESTMENT_APPROVE,
                REPORT_VIEW_ALL, AUDIT_LOG_VIEW, MEMBER_ACCOUNTS_VIEW_ALL)));

        DEFAULTS.put(ChamaRole.CHAIRPERSON, EnumSet.allOf(ChamaPermission.class));

        DEFAULTS.put(ChamaRole.AUDITOR, union(EVERY_MEMBER, EnumSet.of(
                MEMBER_VIEW, CONTRIBUTION_VIEW_ALL, LOAN_VIEW_ALL, PENALTY_VIEW_ALL, LEDGER_VIEW,
                REPORT_VIEW_ALL, AUDIT_LOG_VIEW, MEMBER_ACCOUNTS_VIEW_ALL)));

        // Operations, not money: the admin manages members, their roles, settings and products,
        // but records, approves and disburses nothing. Changing what a role may do
        // (ROLE_PERMISSIONS_MANAGE) stays with the chairperson, so the admin cannot widen its own powers.
        DEFAULTS.put(ChamaRole.ADMIN, union(EVERY_MEMBER, EnumSet.of(
                CHAMA_SETTINGS_MANAGE, MEMBER_VIEW, MEMBER_INVITE, MEMBER_APPROVE, MEMBER_SUSPEND, MEMBER_ROLES_MANAGE,
                CONTRIBUTION_VIEW_ALL, LOAN_VIEW_ALL, PENALTY_VIEW_ALL, REPORT_VIEW_ALL, AUDIT_LOG_VIEW,
                PRODUCTS_MANAGE, MEMBER_ACCOUNTS_VIEW_ALL, MEMBER_ACCOUNTS_MANAGE)));
    }

    private DefaultRolePermissions() {
    }

    public static Set<ChamaPermission> forRole(ChamaRole role) {
        return Collections.unmodifiableSet(EnumSet.copyOf(DEFAULTS.get(role)));
    }

    private static Set<ChamaPermission> union(Set<ChamaPermission> a, Set<ChamaPermission> b) {
        EnumSet<ChamaPermission> result = EnumSet.copyOf(a);
        result.addAll(b);
        return result;
    }
}
