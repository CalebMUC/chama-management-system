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
 * chairperson and secretary approve expenses (two approvers, for the default quorum of two);
 * the secretary handles membership; the auditor sees everything and changes nothing.
 */
public final class DefaultRolePermissions {

    /**
     * The chairperson role can never lose these. Otherwise a chama could remove every way to
     * fix roles and permissions, leaving nobody able to manage it.
     */
    public static final Set<ChamaPermission> LOCKED_FOR_CHAIRPERSON =
            Collections.unmodifiableSet(EnumSet.of(MEMBER_ROLES_MANAGE, ROLE_PERMISSIONS_MANAGE));

    private static final Set<ChamaPermission> EVERY_MEMBER = EnumSet.of(
            CHAMA_VIEW, MEMBER_VIEW, LOAN_APPLY, EXPENSE_VIEW, INVESTMENT_VIEW);

    private static final Map<ChamaRole, Set<ChamaPermission>> DEFAULTS = new EnumMap<>(ChamaRole.class);

    static {
        DEFAULTS.put(ChamaRole.MEMBER, EVERY_MEMBER);

        DEFAULTS.put(ChamaRole.TREASURER, union(EVERY_MEMBER, EnumSet.of(
                CONTRIBUTION_VIEW_ALL, CONTRIBUTION_RECORD, CONTRIBUTION_REVERSE,
                LOAN_VIEW_ALL, LOAN_APPROVE, LOAN_DISBURSE, LOAN_REPAYMENT_RECORD,
                PENALTY_VIEW_ALL, PENALTY_RECORD, EXPENSE_CREATE, INVESTMENT_MANAGE, LEDGER_VIEW, LEDGER_POST, LEDGER_ACCOUNTS_MANAGE,
                REPORT_VIEW_ALL)));

        DEFAULTS.put(ChamaRole.SECRETARY, union(EVERY_MEMBER, EnumSet.of(
                MEMBER_INVITE, CONTRIBUTION_VIEW_ALL, LOAN_VIEW_ALL, PENALTY_VIEW_ALL, EXPENSE_APPROVE,
                REPORT_VIEW_ALL, AUDIT_LOG_VIEW)));

        DEFAULTS.put(ChamaRole.CHAIRPERSON, EnumSet.allOf(ChamaPermission.class));

        DEFAULTS.put(ChamaRole.AUDITOR, union(EVERY_MEMBER, EnumSet.of(
                CONTRIBUTION_VIEW_ALL, LOAN_VIEW_ALL, PENALTY_VIEW_ALL, LEDGER_VIEW,
                REPORT_VIEW_ALL, AUDIT_LOG_VIEW)));
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
