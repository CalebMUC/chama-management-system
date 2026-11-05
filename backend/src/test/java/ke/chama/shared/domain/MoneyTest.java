package ke.chama.shared.domain;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    private static final Currency UGX = Currency.getInstance("UGX");

    @Nested
    class Construction {

        @Test
        void convertsMajorUnitsToCents() {
            assertThat(Money.ofMajor(new BigDecimal("1500.50"), Money.KES)).isEqualTo(Money.kes(150_050));
        }

        @Test
        void respectsCurrenciesWithoutMinorUnits() {
            assertThat(Money.ofMajor(new BigDecimal("5000"), UGX).minorUnits()).isEqualTo(5000);
        }

        @Test
        void rejectsMoreDecimalsThanTheCurrencySupports() {
            assertThatThrownBy(() -> Money.ofMajor(new BigDecimal("10.005"), Money.KES))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void requiresCurrency() {
            assertThatThrownBy(() -> new Money(100, null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    class Arithmetic {

        @Test
        void addsAndSubtracts() {
            assertThat(Money.kes(500_000).plus(Money.kes(25_000)).minus(Money.kes(5_000)))
                    .isEqualTo(Money.kes(520_000));
        }

        @Test
        void refusesToMixCurrencies() {
            assertThatThrownBy(() -> Money.kes(100).plus(Money.of(100, UGX)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Currency mismatch");
        }

        @Test
        void failsLoudlyOnOverflowInsteadOfWrapping() {
            assertThatThrownBy(() -> Money.kes(Long.MAX_VALUE).plus(Money.kes(1)))
                    .isInstanceOf(ArithmeticException.class);
        }

        @Test
        void calculatesLatePenaltyInBasisPoints() {
            // 2% of KES 5,000 = KES 100
            assertThat(Money.kes(500_000).percentage(200, RoundingMode.HALF_EVEN)).isEqualTo(Money.kes(10_000));
        }

        @Test
        void calculatesFlatLoanInterest() {
            // 10% flat on KES 15,000 = KES 1,500
            assertThat(Money.kes(1_500_000).percentage(1_000, RoundingMode.HALF_EVEN)).isEqualTo(Money.kes(150_000));
        }

        @Test
        void roundsPercentagesUsingTheGivenMode() {
            // 2% of 0.25 KES = 0.5 cent
            assertThat(Money.kes(25).percentage(200, RoundingMode.HALF_UP)).isEqualTo(Money.kes(1));
            assertThat(Money.kes(25).percentage(200, RoundingMode.HALF_EVEN)).isEqualTo(Money.kes(0));
        }

        @Test
        void comparesAmounts() {
            assertThat(Money.kes(200).isGreaterThan(Money.kes(100))).isTrue();
            assertThat(Money.kes(-1).isNegative()).isTrue();
            assertThat(Money.zero(Money.KES).isZero()).isTrue();
        }

        @Test
        void formatsForDisplay() {
            assertThat(Money.kes(150_050)).hasToString("KES 1500.50");
        }
    }

    @Nested
    class Allocation {

        @Test
        void splitsEvenlyWithoutLosingACent() {
            Money[] parts = Money.kes(100).allocate(1, 1, 1);

            assertThat(parts).extracting(Money::minorUnits).containsExactly(34L, 33L, 33L);
            assertThat(sum(parts)).isEqualTo(Money.kes(100));
        }

        @Test
        void distributesProfitInProportionToSavings() {
            // Members saved 10k, 20k, 30k; KES 600 profit -> 100, 200, 300
            Money[] parts = Money.kes(60_000).allocate(1_000_000, 2_000_000, 3_000_000);

            assertThat(parts).extracting(Money::minorUnits).containsExactly(10_000L, 20_000L, 30_000L);
        }

        @Test
        void givesLeftoverUnitsToLargestRemainders() {
            // 10 split 1:2:3 -> exact 1.67, 3.33, 5.00 -> floors 1, 3, 5 (sum 9); the extra unit goes to 1.67
            Money[] parts = Money.kes(10).allocate(1, 2, 3);

            assertThat(parts).extracting(Money::minorUnits).containsExactly(2L, 3L, 5L);
        }

        @Test
        void allocatesNegativeAmountsExactly() {
            Money[] parts = Money.kes(-100).allocate(1, 1, 1);

            assertThat(sum(parts)).isEqualTo(Money.kes(-100));
        }

        @Test
        void givesNothingToZeroRatios() {
            Money[] parts = Money.kes(100).allocate(0, 1);

            assertThat(parts).extracting(Money::minorUnits).containsExactly(0L, 100L);
        }

        @Test
        void doesNotOverflowOnLargeBalances() {
            Money[] parts = Money.kes(9_000_000_000_000L).allocate(4_000_000_000_000L, 5_000_000_000_000L);

            assertThat(sum(parts)).isEqualTo(Money.kes(9_000_000_000_000L));
        }

        @Test
        void rejectsInvalidRatios() {
            assertThatThrownBy(() -> Money.kes(100).allocate()).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Money.kes(100).allocate(0, 0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Money.kes(100).allocate(1, -1)).isInstanceOf(IllegalArgumentException.class);
        }

        private Money sum(Money[] parts) {
            return Arrays.stream(parts).reduce(Money.zero(Money.KES), Money::plus);
        }
    }
}
