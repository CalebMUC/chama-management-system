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
                .contains(CONTRIBUTION_RECORD, LOAN_DISBURSE, EXPENSE_CREATE, LEDGER_VIEW, DATA_IMPORT)
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
    void adminRunsOperationsButDoesNotHandleMoneyOrReshapeRoles() {
        assertThat(DefaultRolePermissions.forRole(ChamaRole.ADMIN))
                .contains(MEMBER_VIEW, MEMBER_INVITE, MEMBER_APPROVE, MEMBER_SUSPEND, MEMBER_ROLES_MANAGE,
                        CHAMA_SETTINGS_MANAGE, PRODUCTS_MANAGE, MEMBER_ACCOUNTS_MANAGE)
                .doesNotContain(CONTRIBUTION_RECORD, LOAN_APPROVE, LOAN_DISBURSE, EXPENSE_CREATE, EXPENSE_APPROVE,
                        INVESTMENT_MANAGE, INVESTMENT_APPROVE, LEDGER_POST, PENALTY_WAIVE, ROLE_PERMISSIONS_MANAGE);
    }

    /**
     * V22 grants ADMIN to chamas that existed before it, V25 adds DATA_IMPORT to every role holding
     * CHAMA_SETTINGS_MANAGE and V26 the member-exit permissions; new chamas get DefaultRolePermissions.
     * Keep them equal.
     */
    @Test
    void theAdminMigrationGrantsTheSameDefaults() throws Exception {
        String sql;
        try (var in = getClass().getResourceAsStream("/db/migration/V22__chama_admin_role.sql")) {
            sql = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        java.util.Set<String> granted = java.util.regex.Pattern.compile("\\('([A-Z_]+)'\\)")
                .matcher(sql.substring(sql.indexOf("CROSS JOIN")))
                .results().map(m -> m.group(1)).collect(java.util.stream.Collectors.toSet());
        assertThat(granted).contains("CHAMA_SETTINGS_MANAGE");
        granted.add("DATA_IMPORT");
        granted.addAll(v26Grants().getOrDefault("ADMIN", java.util.Set.of()));
        assertThat(granted).isEqualTo(DefaultRolePermissions.forRole(ChamaRole.ADMIN).stream()
                .map(Enum::name).collect(java.util.stream.Collectors.toSet()));
    }

    /** V25 grants DATA_IMPORT to existing chamas: wherever CHAMA_SETTINGS_MANAGE is held, and to the treasurer. */
    @Test
    void dataImportGoesWhereChamaSettingsWentPlusTheTreasurer() {
        for (ChamaRole role : ChamaRole.values()) {
            boolean expected = role == ChamaRole.TREASURER
                    || DefaultRolePermissions.forRole(role).contains(CHAMA_SETTINGS_MANAGE);
            assertThat(DefaultRolePermissions.forRole(role).contains(DATA_IMPORT)).as(role.name()).isEqualTo(expected);
        }
    }

    /** Exits follow expenses: the treasurer (or admin) starts, the chair and secretary approve, the treasurer pays. */
    @Test
    void memberExitsSeparateStartingApprovingAndPayingOut() {
        assertThat(DefaultRolePermissions.forRole(ChamaRole.TREASURER)).contains(MEMBER_EXIT_REQUEST, MEMBER_EXIT_SETTLE)
                .doesNotContain(MEMBER_EXIT_APPROVE);
        assertThat(DefaultRolePermissions.forRole(ChamaRole.SECRETARY)).contains(MEMBER_EXIT_APPROVE)
                .doesNotContain(MEMBER_EXIT_REQUEST, MEMBER_EXIT_SETTLE);
        assertThat(DefaultRolePermissions.forRole(ChamaRole.ADMIN)).contains(MEMBER_EXIT_REQUEST)
                .doesNotContain(MEMBER_EXIT_APPROVE, MEMBER_EXIT_SETTLE);
        assertThat(DefaultRolePermissions.forRole(ChamaRole.MEMBER))
                .doesNotContain(MEMBER_EXIT_REQUEST, MEMBER_EXIT_APPROVE, MEMBER_EXIT_SETTLE);
    }

    /** V26 grants the member-exit permissions to existing chamas exactly as new chamas get them. */
    @Test
    void theMemberExitMigrationGrantsTheSameDefaults() throws Exception {
        java.util.Map<String, java.util.Set<String>> granted = v26Grants();
        for (ChamaRole role : ChamaRole.values()) {
            java.util.Set<String> expected = DefaultRolePermissions.forRole(role).stream().map(Enum::name)
                    .filter(p -> p.startsWith("MEMBER_EXIT_")).collect(java.util.stream.Collectors.toSet());
            assertThat(granted.getOrDefault(role.name(), java.util.Set.of())).as(role.name()).isEqualTo(expected);
        }
    }

    private java.util.Map<String, java.util.Set<String>> v26Grants() throws Exception {
        String sql;
        try (var in = getClass().getResourceAsStream("/db/migration/V26__member_exits.sql")) {
            sql = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        java.util.Map<String, java.util.Set<String>> granted = new java.util.HashMap<>();
        java.util.regex.Pattern.compile("\\('([A-Z]+)', '([A-Z_]+)'\\)").matcher(sql.substring(sql.indexOf("CROSS JOIN")))
                .results().forEach(m -> granted.computeIfAbsent(m.group(1), k -> new java.util.HashSet<>()).add(m.group(2)));
        return granted;
    }

    @Test
    void chairpersonHoldsEveryPermission() {
        assertThat(DefaultRolePermissions.forRole(ChamaRole.CHAIRPERSON)).containsExactlyInAnyOrder(ChamaPermission.values());
        assertThat(DefaultRolePermissions.forRole(ChamaRole.CHAIRPERSON))
                .containsAll(DefaultRolePermissions.LOCKED_FOR_CHAIRPERSON);
    }
}
