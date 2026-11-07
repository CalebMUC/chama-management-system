package ke.chama.modules.account.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SavingsProductTest {

    private static final UUID CHAMA = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-07T08:00:00Z");

    @Test
    void aNewProductTakesItsKindsDefaultsUnlessToldOtherwise() {
        SavingsProduct shares = SavingsProduct.create(CHAMA, " shr ", SavingsProductKind.SHARE_CAPITAL,
                new SavingsProduct.Terms("Share capital", 50_000, 20, true, null, null, null), "3100", null, NOW);

        assertThat(shares.code()).isEqualTo("SHR");
        assertThat(shares.countsTowardLoanLimit()).isFalse();
        assertThat(shares.refundableOnExit()).isFalse();
        assertThat(shares.earnsDividend()).isTrue();
        assertThat(shares.defaultProduct()).isFalse();
        assertThat(shares.active()).isTrue();

        SavingsProduct backing = SavingsProduct.create(CHAMA, "SHR2", SavingsProductKind.SHARE_CAPITAL,
                new SavingsProduct.Terms("Shares that back loans", 0, 20, false, true, null, null), "3110", null, NOW);
        assertThat(backing.countsTowardLoanLimit()).isTrue();
    }

    @Test
    void codesNamesAndAmountsAreChecked() {
        SavingsProduct.Terms ok = new SavingsProduct.Terms("Welfare", 20_000, 10, true, null, null, null);
        assertThatThrownBy(() -> SavingsProduct.create(CHAMA, "1X", SavingsProductKind.WELFARE, ok, "2010", null, NOW))
                .extracting("code").isEqualTo("SAVINGS_PRODUCT_INVALID");
        assertThatThrownBy(() -> SavingsProduct.create(CHAMA, "WLF", SavingsProductKind.WELFARE,
                new SavingsProduct.Terms(" ", 0, 10, true, null, null, null), "2010", null, NOW))
                .hasMessageContaining("name");
        assertThatThrownBy(() -> SavingsProduct.create(CHAMA, "WLF", SavingsProductKind.WELFARE,
                new SavingsProduct.Terms("Welfare", -1, 10, true, null, null, null), "2010", null, NOW))
                .hasMessageContaining("negative");
        assertThatThrownBy(() -> SavingsProduct.create(CHAMA, "WLF", SavingsProductKind.WELFARE,
                new SavingsProduct.Terms("Welfare", 0, 100, true, null, null, null), "2010", null, NOW))
                .hasMessageContaining("priority");
    }

    @Test
    void theDefaultProductFollowsTheSettingsAndStaysOpenToEveryone() {
        SavingsProduct deposits = SavingsProduct.defaultDeposits(CHAMA, "2000", 500_000, NOW);

        SavingsProduct renamed = deposits.update(new SavingsProduct.Terms("Monthly savings", 500_000, 50, true, null, null, null), NOW);
        assertThat(renamed.name()).isEqualTo("Monthly savings");

        assertThatThrownBy(() -> deposits.update(new SavingsProduct.Terms("Deposits", 600_000, 50, true, null, null, null), NOW))
                .extracting("code").isEqualTo("SAVINGS_PRODUCT_DEFAULT_RULE");
        assertThatThrownBy(() -> deposits.update(new SavingsProduct.Terms("Deposits", 500_000, 50, false, null, null, null), NOW))
                .hasMessageContaining("every member");
        assertThatThrownBy(() -> deposits.withActive(false, NOW)).hasMessageContaining("cannot be deactivated");

        assertThat(deposits.followContributionAmount(600_000, NOW).contributionMinor()).isEqualTo(600_000);
    }

    @Test
    void anAccountOwesForPeriodsWhileItIsOpen() {
        UUID member = UUID.randomUUID();
        MemberAccount account = MemberAccount.open(CHAMA, "SHR", 7, member, UUID.randomUUID(),
                LocalDate.of(2026, 9, 1), null, NOW);
        assertThat(account.accountNumber()).isEqualTo("SHR-0007");
        assertThat(MemberAccount.number("SHR", null, member)).isEqualTo("SHR-" + member.toString().substring(0, 8));

        MemberAccount closed = account.close(UUID.randomUUID(), "Moved to another product", LocalDate.of(2026, 11, 3));
        assertThat(closed.active()).isFalse();
        assertThatThrownBy(() -> closed.close(UUID.randomUUID(), "again", LocalDate.of(2026, 11, 4)))
                .extracting("code").isEqualTo("MEMBER_ACCOUNT_STATE");
        assertThatThrownBy(() -> account.close(UUID.randomUUID(), " ", LocalDate.of(2026, 11, 4)))
                .hasMessageContaining("reason");

        MemberAccount reopened = closed.reopen(UUID.randomUUID(), LocalDate.of(2026, 12, 10));
        assertThat(reopened.active()).isTrue();
        assertThat(reopened.openedOn()).isEqualTo(LocalDate.of(2026, 12, 10));
        assertThat(reopened.closedOn()).isNull();
    }
}
