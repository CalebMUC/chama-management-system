package ke.chama.modules.member.domain.model;

import ke.chama.shared.security.ChamaPermission;
import org.junit.jupiter.api.Test;

import static ke.chama.shared.security.ChamaPermission.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Pins down the separation of duties the defaults promise. */
class DefaultRolePermissionsTest {

    @Test
    void ordinaryMembersSeeButDoNotTouchMoney() {
        assertThat(DefaultRolePermissions.forRole(ChamaRole.MEMBER))
                .contains(CHAMA_VIEW, LOAN_APPLY, PAYMENT_SUBMIT)
                .doesNotContain(CONTRIBUTION_RECORD, CONTRIBUTION_VIEW_ALL, LOAN_APPROVE, EXPENSE_APPROVE,
                        MEMBER_VIEW, MEMBER_INVITE, AUDIT_LOG_VIEW, CHAMA_SETTINGS_MANAGE);
    }

    @Test
    void treasurerRecordsMoneyButCannotApproveExpensesOrManageMembers() {
        assertThat(DefaultRolePermissions.forRole(ChamaRole.TREASURER))
                .contains(CONTRIBUTION_RECORD, LOAN_DISBURSE, EXPENSE_CREATE, LEDGER_VIEW)
                .doesNotContain(EXPENSE_APPROVE, PENALTY_WAIVE, MEMBER_ROLES_MANAGE, CHAMA_SETTINGS_MANAGE);
    }

    @Test
    void secretaryHandlesMembershipAndApprovesSpendingButDoesNotHandleMoney() {
        assertThat(DefaultRolePermissions.forRole(ChamaRole.SECRETARY))
                .contains(MEMBER_INVITE, AUDIT_LOG_VIEW, EXPENSE_APPROVE, INVESTMENT_APPROVE)
                .doesNotContain(CONTRIBUTION_RECORD, LOAN_DISBURSE, MEMBER_ROLES_MANAGE);
    }

    @Test
    void auditorSeesEverythingAndChangesNothing() {
        assertThat(DefaultRolePermissions.forRole(ChamaRole.AUDITOR))
                .contains(CONTRIBUTION_VIEW_ALL, LOAN_VIEW_ALL, LEDGER_VIEW, AUDIT_LOG_VIEW)
                .doesNotContain(CONTRIBUTION_RECORD, LOAN_APPROVE, EXPENSE_CREATE, MEMBER_INVITE);
    }

    @Test
    void chairpersonHoldsEveryPermission() {
        assertThat(DefaultRolePermissions.forRole(ChamaRole.CHAIRPERSON)).containsExactlyInAnyOrder(ChamaPermission.values());
        assertThat(DefaultRolePermissions.forRole(ChamaRole.CHAIRPERSON))
                .containsAll(DefaultRolePermissions.LOCKED_FOR_CHAIRPERSON);
    }
}
