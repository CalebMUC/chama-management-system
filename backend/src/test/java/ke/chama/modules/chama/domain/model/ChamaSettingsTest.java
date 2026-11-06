package ke.chama.modules.chama.domain.model;

import ke.chama.shared.domain.exception.DomainValidationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChamaSettingsTest {

    @Test
    void standardDefaultsMatchTheDocumentedBusinessRules() {
        ChamaSettings standard = ChamaSettings.standard();

        assertThat(standard.contributionAmountMinor()).isEqualTo(500_000);   // KES 5,000
        assertThat(standard.latePenaltyBasisPoints()).isEqualTo(200);        // 2%
        assertThat(standard.loanMaxMultiplier()).isEqualTo(3);               // 3x savings
        assertThat(standard.loanInterestBasisPoints()).isEqualTo(1_000);     // 10% flat
        assertThat(standard.loanMinMembershipMonths()).isEqualTo(3);
        assertThat(standard.loanGuarantorThresholdMinor()).isEqualTo(5_000_000); // KES 50,000
        assertThat(standard.loanGuarantorsRequired()).isEqualTo(2);
        assertThat(standard.approvalQuorum()).isEqualTo(2);
    }

    @Test
    void monthlyDueDayStopsAt28SoEveryMonthHasIt() {
        assertThatThrownBy(() -> settings(ContributionFrequency.MONTHLY, 29, 500_000))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("between 1 and 28");
    }

    @Test
    void weeklyDueDayIsADayOfWeek() {
        assertThat(settings(ContributionFrequency.WEEKLY, 7, 100_000).contributionDueDay()).isEqualTo(7);
        assertThatThrownBy(() -> settings(ContributionFrequency.WEEKLY, 8, 100_000))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void contributionMustBePositive() {
        assertThatThrownBy(() -> settings(ContributionFrequency.MONTHLY, 5, 0))
                .isInstanceOf(DomainValidationException.class)
                .extracting("code").isEqualTo("CHAMA_INVALID_SETTINGS");
    }

    private static ChamaSettings settings(ContributionFrequency frequency, int dueDay, long amount) {
        return new ChamaSettings(amount, frequency, dueDay, 5, 200, 3, 1_000, 3, 5_000_000, 2, 2);
    }
}
