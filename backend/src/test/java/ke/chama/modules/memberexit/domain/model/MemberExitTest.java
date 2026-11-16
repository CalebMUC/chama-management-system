package ke.chama.modules.memberexit.domain.model;

import ke.chama.modules.memberexit.domain.model.Settlement.Kind;
import ke.chama.modules.memberexit.domain.model.Settlement.Line;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemberExitTest {

    private static final UUID CHAMA = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID TREASURER = UUID.randomUUID();
    private static final UUID CHAIR = UUID.randomUUID();
    private static final UUID SECRETARY = UUID.randomUUID();
    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    private static final Instant NOW = Instant.parse("2026-11-16T07:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 16);

    @Test
    void aQuorumOfOfficialsOtherThanTheRequesterAndTheMemberApproves() {
        MemberExit exit = request(1_000_000);

        assertThatThrownBy(() -> exit.approve(TREASURER, null, NOW)).extracting("code").isEqualTo("EXIT_SELF_HANDLING");
        assertThatThrownBy(() -> exit.approve(MEMBER, null, NOW)).extracting("code").isEqualTo("EXIT_SELF_HANDLING");

        exit.approve(CHAIR, "Agreed at the AGM", NOW);
        assertThat(exit.status()).isEqualTo(MemberExit.Status.PENDING_APPROVAL);
        assertThatThrownBy(() -> exit.approve(CHAIR, null, NOW)).extracting("code").isEqualTo("EXIT_ALREADY_APPROVED");
        exit.approve(SECRETARY, null, NOW);
        assertThat(exit.status()).isEqualTo(MemberExit.Status.APPROVED);
    }

    @Test
    void nobodyStartsTheirOwnExitOrOneWithAShortfall() {
        Settlement fine = settlement(1_000_000);
        assertThatThrownBy(() -> MemberExit.request(CHAMA, MEMBER, MEMBER, "Leaving", fine, 2, NOW))
                .extracting("code").isEqualTo("EXIT_SELF_HANDLING");

        Settlement owing = new Settlement("KES", List.of(new Line(Kind.PENALTY, UUID.randomUUID(), null, "Fine", 500)));
        assertThatThrownBy(() -> MemberExit.request(CHAMA, MEMBER, TREASURER, "Leaving", owing, 2, NOW))
                .extracting("code").isEqualTo("EXIT_INVALID");
        assertThatThrownBy(() -> MemberExit.request(CHAMA, MEMBER, TREASURER, " ", fine, 2, NOW))
                .extracting("code").isEqualTo("EXIT_INVALID");
    }

    @Test
    void onlyAnApprovedExitIsSettledAndAPayoutNeedsAMethodAndReference() {
        MemberExit exit = request(1_000_000);
        assertThatThrownBy(() -> exit.settle(TREASURER, "CASH", null, null, TODAY, NAIROBI)).extracting("code")
                .isEqualTo("EXIT_WRONG_STATUS");
        exit.approve(CHAIR, null, NOW);
        exit.approve(SECRETARY, null, NOW);

        assertThatThrownBy(() -> exit.settle(TREASURER, null, null, null, TODAY, NAIROBI)).extracting("code")
                .isEqualTo("EXIT_INVALID");
        assertThatThrownBy(() -> exit.settle(TREASURER, "MPESA", null, null, TODAY, NAIROBI))
                .hasMessageContaining("reference");
        assertThatThrownBy(() -> exit.settle(TREASURER, "CASH", null, TODAY.plusDays(1), TODAY, NAIROBI))
                .hasMessageContaining("future");
        assertThatThrownBy(() -> exit.settle(TREASURER, "CASH", null, TODAY.minusDays(1), TODAY, NAIROBI))
                .hasMessageContaining("before the exit was started");
        assertThatThrownBy(() -> exit.settle(MEMBER, "CASH", null, null, TODAY, NAIROBI)).extracting("code")
                .isEqualTo("EXIT_SELF_HANDLING");

        exit.settle(TREASURER, "mpesa", "qkexit01", null, TODAY, NAIROBI);
        assertThat(exit.status()).isEqualTo(MemberExit.Status.SETTLED);
        assertThat(exit.paymentMethod()).isEqualTo("MPESA");
        assertThat(exit.paymentReference()).isEqualTo("QKEXIT01");
        assertThat(exit.settledOn()).isEqualTo(TODAY);
    }

    @Test
    void nothingToPayOutNeedsNoMethod() {
        MemberExit exit = MemberExit.request(CHAMA, MEMBER, TREASURER, "Leaving", new Settlement("KES", List.of()), 1, NOW);
        exit.approve(CHAIR, null, NOW);

        exit.settle(TREASURER, "MPESA", "IGNORED", null, TODAY, NAIROBI);

        assertThat(exit.status()).isEqualTo(MemberExit.Status.SETTLED);
        assertThat(exit.paymentMethod()).isNull();
        assertThat(exit.paymentReference()).isNull();
    }

    @Test
    void theRequesterCancelsAndAnApproverRejectsWithAReason() {
        MemberExit cancelled = request(1_000);
        assertThatThrownBy(() -> cancelled.cancel(CHAIR, NOW)).extracting("code").isEqualTo("EXIT_NOT_REQUESTER");
        cancelled.cancel(TREASURER, NOW);
        assertThat(cancelled.status()).isEqualTo(MemberExit.Status.CANCELLED);
        assertThat(cancelled.inProgress()).isFalse();

        MemberExit rejected = request(1_000);
        assertThatThrownBy(() -> rejected.reject(CHAIR, " ", NOW)).extracting("code").isEqualTo("EXIT_INVALID");
        assertThatThrownBy(() -> rejected.reject(TREASURER, "No", NOW)).extracting("code").isEqualTo("EXIT_SELF_HANDLING");
        rejected.reject(CHAIR, "Clear the welfare dispute first", NOW);
        assertThat(rejected.status()).isEqualTo(MemberExit.Status.REJECTED);
        assertThat(rejected.rejectionReason()).isEqualTo("Clear the welfare dispute first");
    }

    private static MemberExit request(long deposits) {
        return MemberExit.request(CHAMA, MEMBER, TREASURER, " Relocating ", settlement(deposits), 2, NOW);
    }

    private static Settlement settlement(long deposits) {
        return new Settlement("KES", List.of(new Line(Kind.REFUND, UUID.randomUUID(), "2000", "Deposits", deposits)));
    }
}
