package ke.chama.modules.loan.domain.model;

import ke.chama.modules.chama.api.LoanRules;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoanEligibilityTest {

    private static final LoanRules RULES = new LoanRules("KES", 3, 1_000, 3, 5_000_000, 2, 2);
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 6);

    @Test
    void anEstablishedMemberMayBorrowThreeTimesTheirSavings() {
        LoanEligibility e = LoanEligibility.assess(RULES, true, 2_000_000, TODAY.minusMonths(4), TODAY, false);

        assertThat(e.eligible()).isTrue();
        assertThat(e.maxLoanMinor()).isEqualTo(6_000_000);
        assertThat(e.membershipMonths()).isEqualTo(4);
        e.requireAllows(6_000_000);
        assertThatThrownBy(() -> e.requireAllows(6_000_001))
                .extracting("code").isEqualTo("LOAN_NOT_ELIGIBLE");
        assertThatThrownBy(() -> e.requireAllows(6_000_001))
                .hasMessageContaining("The most you can borrow is KES 60,000.00 (3× your savings of KES 20,000.00)");
    }

    @Test
    void everyReasonNotToLendIsListed() {
        LoanEligibility e = LoanEligibility.assess(RULES, false, 0, TODAY.minusMonths(2), TODAY, true);

        assertThat(e.eligible()).isFalse();
        assertThat(e.reasons()).hasSize(4);
        assertThat(String.join(" ", e.reasons()))
                .contains("Only active members", "after 3 months of membership; you have 2", "need savings",
                        "already have a loan");
    }

    @Test
    void loansOfOtherProductsUseUpPartOfTheLimit() {
        // KES 20,000 of savings × 3 = KES 60,000, less KES 45,000 still owed on a normal loan.
        LoanEligibility e = LoanEligibility.assess(RULES, "Emergency loan", 3, true, 2_000_000, 4_500_000,
                TODAY.minusMonths(4), TODAY, false);

        assertThat(e.eligible()).isTrue();
        assertThat(e.maxLoanMinor()).isEqualTo(1_500_000);
        e.requireAllows(1_500_000, 3);
        assertThatThrownBy(() -> e.requireAllows(1_500_001, 3))
                .hasMessageContaining("less KES 45,000.00 on your other loans");
        assertThatThrownBy(() -> e.requireAllows(1_000_000, 4))
                .hasMessageContaining("The longest term for an emergency loan is 3 months");

        LoanEligibility full = LoanEligibility.assess(RULES, "Emergency loan", 3, true, 2_000_000, 6_000_000,
                TODAY.minusMonths(4), TODAY, true);
        assertThat(full.maxLoanMinor()).isZero();
        assertThat(String.join(" ", full.reasons())).contains("already use up 3× your savings",
                "already have an emergency loan in progress");
    }

    @Test
    void membershipCountsWholeMonths() {
        assertThat(LoanEligibility.assess(RULES, true, 1, TODAY.minusMonths(3).plusDays(1), TODAY, false).eligible())
                .isFalse();
        assertThat(LoanEligibility.assess(RULES, true, 1, TODAY.minusMonths(3), TODAY, false).eligible()).isTrue();
    }
}
