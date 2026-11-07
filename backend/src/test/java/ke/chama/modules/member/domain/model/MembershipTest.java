package ke.chama.modules.member.domain.model;

import ke.chama.modules.member.domain.exception.InvalidMembershipStateException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MembershipTest {

    private static final Instant NOW = Instant.parse("2026-11-05T09:00:00Z");
    private static final UUID CHAMA = UUID.randomUUID();
    private static final UUID SECRETARY = UUID.randomUUID();
    private static final UUID CHAIR = UUID.randomUUID();
    private static final UUID APPLICANT = UUID.randomUUID();

    @Test
    void founderIsActiveChairpersonNumberOne() {
        Membership founder = Membership.founder(CHAMA, CHAIR, "Chair@Example.com", NOW);

        assertThat(founder.isActive()).isTrue();
        assertThat(founder.getMemberNumber()).isEqualTo(1);
        assertThat(founder.getRoles()).containsExactlyInAnyOrder(ChamaRole.MEMBER, ChamaRole.CHAIRPERSON);
        assertThat(founder.getEmail()).isEqualTo("chair@example.com");
    }

    @Test
    void applicationThenApprovalRecordsWhoDidWhat() {
        Membership membership = Membership.application(CHAMA, APPLICANT, "jane@example.com", NOW);
        assertThat(membership.getStatus()).isEqualTo(MembershipStatus.PENDING_APPROVAL);
        assertThat(membership.belongsTo(APPLICANT)).isTrue();
        assertThat(membership.getMemberNumber()).isNull();

        membership.approve(CHAIR, 7, NOW.plusSeconds(120));
        assertThat(membership.isActive()).isTrue();
        assertThat(membership.getMemberNumber()).isEqualTo(7);
        assertThat(membership.getJoinedAt()).isEqualTo(NOW.plusSeconds(120));
        assertThat(membership.getRoles()).containsExactly(ChamaRole.MEMBER);
    }

    @Test
    void anApprovedMembershipCannotBeApprovedAgain() {
        Membership membership = Membership.application(CHAMA, APPLICANT, "jane@example.com", NOW);
        membership.approve(CHAIR, 2, NOW);

        assertThatThrownBy(() -> membership.approve(CHAIR, 3, NOW))
                .isInstanceOf(InvalidMembershipStateException.class)
                .hasMessageContaining("ACTIVE");
    }

    @Test
    void rejectedApplicantsGetNoMemberNumber() {
        Membership membership = Membership.application(CHAMA, APPLICANT, "jane@example.com", NOW);

        membership.reject(SECRETARY, NOW);

        assertThat(membership.getStatus()).isEqualTo(MembershipStatus.REJECTED);
        assertThat(membership.getMemberNumber()).isNull();
    }

    @Test
    void suspendAndReactivate() {
        Membership membership = Membership.founder(CHAMA, CHAIR, "c@example.com", NOW);

        membership.suspend(SECRETARY, "  Missed 3 meetings ", NOW);
        assertThat(membership.getStatus()).isEqualTo(MembershipStatus.SUSPENDED);
        assertThat(membership.getSuspensionReason()).isEqualTo("Missed 3 meetings");

        membership.reactivate();
        assertThat(membership.isActive()).isTrue();
        assertThat(membership.getSuspensionReason()).isNull();
    }

    @Test
    void changingRolesAlwaysKeepsMember() {
        Membership membership = Membership.founder(CHAMA, CHAIR, "c@example.com", NOW);

        membership.changeRoles(Set.of(ChamaRole.TREASURER));

        assertThat(membership.getRoles()).containsExactlyInAnyOrder(ChamaRole.MEMBER, ChamaRole.TREASURER);
    }

    @Test
    void rolesOfAPendingMembershipCannotChange() {
        Membership membership = Membership.application(CHAMA, APPLICANT, "jane@example.com", NOW);

        assertThatThrownBy(() -> membership.changeRoles(Set.of(ChamaRole.TREASURER)))
                .isInstanceOf(InvalidMembershipStateException.class);
    }
}
