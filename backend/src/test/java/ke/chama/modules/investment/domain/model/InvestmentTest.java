package ke.chama.modules.investment.domain.model;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvestmentTest {

    private static final UUID CHAMA = UUID.randomUUID();
    private static final UUID TREASURER = UUID.randomUUID();
    private static final UUID CHAIR = UUID.randomUUID();
    private static final UUID SECRETARY = UUID.randomUUID();
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 6);
    private static final Instant NOW = Instant.parse("2026-11-06T09:00:00Z");

    @Nested
    class Proposing {

        @Test
        void aProposalMustMakeSense() {
            assertThatThrownBy(() -> propose(" ", Investment.Type.LAND, 100, null)).hasMessage("A name is required");
            assertThatThrownBy(() -> propose("Plot", null, 100, null)).hasMessage("A type is required");
            assertThatThrownBy(() -> propose("Plot", Investment.Type.LAND, 0, null)).extracting("code")
                    .isEqualTo("INVESTMENT_INVALID");
            assertThatThrownBy(() -> propose("T-bill", Investment.Type.TREASURY_BILL, 100, TODAY))
                    .hasMessageContaining("maturity date must be in the future");

            assertThat(propose("T-bill", Investment.Type.TREASURY_BILL, 100, TODAY.plusDays(91)).status())
                    .isEqualTo(Investment.Status.PROPOSED);
        }

        @Test
        void aQuorumOfOfficialsOtherThanTheProposerApproves() {
            Investment investment = proposed();

            assertThatThrownBy(() -> investment.approve(TREASURER, null, NOW)).extracting("code")
                    .isEqualTo("INVESTMENT_SELF_APPROVAL");
            investment.approve(CHAIR, null, NOW);
            assertThatThrownBy(() -> investment.approve(CHAIR, null, NOW)).extracting("code")
                    .isEqualTo("INVESTMENT_ALREADY_APPROVED");
            investment.approve(SECRETARY, null, NOW);

            assertThat(investment.status()).isEqualTo(Investment.Status.APPROVED);
        }

        @Test
        void rejectingNeedsAReasonAndOnlyTheProposerCancels() {
            Investment rejected = proposed();
            assertThatThrownBy(() -> rejected.reject(TREASURER, "No", NOW)).extracting("code")
                    .isEqualTo("INVESTMENT_SELF_APPROVAL");
            rejected.reject(CHAIR, "Too risky", NOW);
            assertThat(rejected.status()).isEqualTo(Investment.Status.REJECTED);

            Investment cancelled = proposed();
            assertThatThrownBy(() -> cancelled.cancel(CHAIR, NOW)).extracting("code").isEqualTo("INVESTMENT_NOT_PROPOSER");
            cancelled.cancel(TREASURER, NOW);
            assertThat(cancelled.status()).isEqualTo(Investment.Status.CANCELLED);
        }
    }

    @Nested
    class Money {

        @Test
        void fundingNeedsApprovalAndPutsTheApprovedAmountIn() {
            Investment investment = proposed();
            assertThatThrownBy(() -> investment.fund(TREASURER, "CASH", null, null, TODAY, NOW)).extracting("code")
                    .isEqualTo("INVESTMENT_WRONG_STATUS");
            approve(investment);

            InvestmentTransaction funding = investment.fund(TREASURER, "MPESA", "qkfund01", null, TODAY, NOW);

            assertThat(funding.amountMinor()).isEqualTo(5_000_000);
            assertThat(funding.reference()).isEqualTo("QKFUND01");
            assertThat(investment.status()).isEqualTo(Investment.Status.ACTIVE);
            assertThat(investment.bookValueMinor()).isEqualTo(5_000_000);
        }

        @Test
        void reinvestedIncomeAddsToTheBookValueAndPaidOutIncomeDoesNot() {
            Investment investment = funded();

            investment.recordIncome(TREASURER, 50_000, "REINVESTED", null, null, "October interest", TODAY, NOW);
            investment.recordIncome(TREASURER, 20_000, "MPESA", "QKINT01", null, null, TODAY, NOW);

            assertThat(investment.bookValueMinor()).isEqualTo(5_050_000);
            assertThat(investment.incomeMinor()).isEqualTo(70_000);
        }

        @Test
        void withdrawalsAreLessThanTheBookValueAndNeverReinvested() {
            Investment investment = funded();

            assertThatThrownBy(() -> investment.withdraw(TREASURER, 5_000_000, "CASH", null, null, null, TODAY, NOW))
                    .hasMessageContaining("close the investment");
            assertThatThrownBy(() -> investment.withdraw(TREASURER, 100, "REINVESTED", null, null, null, TODAY, NOW))
                    .hasMessageContaining("Only income can be REINVESTED");
            investment.withdraw(TREASURER, 1_000_000, "CASH", null, null, null, TODAY, NOW);

            assertThat(investment.bookValueMinor()).isEqualTo(4_000_000);
            assertThat(investment.withdrawnMinor()).isEqualTo(1_000_000);
        }

        @Test
        void closingRealisesTheGainOrLossAgainstTheBookValue() {
            Investment gain = funded();
            gain.close(TREASURER, 5_400_000, "CASH", null, null, null, TODAY, NOW);
            assertThat(gain.realisedGainMinor()).isEqualTo(400_000);
            assertThat(gain.bookValueMinor()).isZero();
            assertThat(gain.status()).isEqualTo(Investment.Status.CLOSED);

            Investment loss = funded();
            loss.close(TREASURER, 4_500_000, "CASH", null, null, null, TODAY, NOW);
            assertThat(loss.realisedGainMinor()).isEqualTo(-500_000);
        }

        @Test
        void depositsTopUpAnActiveInvestmentWithoutNewApproval() {
            Investment investment = funded();

            investment.deposit(TREASURER, 1_000_000, "MPESA", "QKTOPUP1", null, "November contributions", TODAY, NOW);

            assertThat(investment.bookValueMinor()).isEqualTo(6_000_000);
            assertThat(investment.depositedMinor()).isEqualTo(1_000_000);
            assertThat(investment.investedMinor()).isEqualTo(6_000_000);
            assertThatThrownBy(() -> investment.deposit(TREASURER, 100, "REINVESTED", null, null, null, TODAY, NOW))
                    .hasMessageContaining("Only income can be REINVESTED");
        }

        @Test
        void moneyMovingInOrOutMovesTheLatestValuationTooSoItIsNeverAGainOrLoss() {
            Investment investment = funded();
            investment.value(TREASURER, 5_100_000, TODAY, null, TODAY, NOW);   // 100,000 unrealised gain

            investment.withdraw(TREASURER, 1_000_000, "CASH", null, null, null, TODAY, NOW);
            assertThat(investment.latestValuationMinor()).isEqualTo(4_100_000);
            investment.deposit(TREASURER, 500_000, "CASH", null, null, null, TODAY, NOW);
            investment.recordIncome(TREASURER, 20_000, "REINVESTED", null, null, null, TODAY, NOW);

            assertThat(investment.latestValuationMinor()).isEqualTo(4_620_000);
            assertThat(investment.bookValueMinor()).isEqualTo(4_520_000);
            assertThat(investment.unrealisedGainMinor()).isEqualTo(100_000);
        }

        @Test
        void profitIsIncomeLessExpensesPlusTheGainInValue() {
            Investment investment = funded();                                       // KES 50,000, 30 days ago
            investment.recordIncome(TREASURER, 50_000, "REINVESTED", null, null, null, TODAY, NOW);
            investment.recordIncome(TREASURER, 20_000, "CASH", null, null, null, TODAY, NOW);
            investment.value(TREASURER, 5_150_000, TODAY, null, TODAY, NOW);         // book 5,050,000

            Investment.Performance p = investment.performance(30_000, TODAY);

            assertThat(p.investedMinor()).isEqualTo(5_000_000);
            assertThat(p.netIncomeMinor()).isEqualTo(70_000 - 30_000);
            assertThat(p.gainMinor()).isEqualTo(100_000);
            assertThat(p.totalReturnMinor()).isEqualTo(140_000);
            assertThat(p.returnBasisPoints()).isEqualTo(280);                        // 2.8%
            assertThat(p.daysHeld()).isEqualTo(30);
            assertThat(p.annualisedReturnBasisPoints()).isEqualTo(3_407);            // 2.8% x 365 / 30
            assertThat(p.profitable()).isTrue();

            assertThat(investment.performance(200_000, TODAY).profitable()).as("expenses above income and gain").isFalse();
            assertThat(proposed().performance(0, TODAY)).as("not funded yet").isNull();
        }

        @Test
        void aClosedInvestmentsProfitUsesTheRealisedGain() {
            Investment investment = funded();
            investment.recordIncome(TREASURER, 100_000, "CASH", null, null, null, TODAY, NOW);
            investment.close(TREASURER, 4_800_000, "CASH", null, null, null, TODAY, NOW);

            Investment.Performance p = investment.performance(0, TODAY.plusYears(1));

            assertThat(p.gainMinor()).isEqualTo(-200_000);
            assertThat(p.gainRealised()).isTrue();
            assertThat(p.totalReturnMinor()).isEqualTo(-100_000);
            assertThat(p.daysHeld()).as("held until closed, not until today").isEqualTo(30);
            assertThat(p.profitable()).isFalse();
        }

        @Test
        void valuationsShowTheUnrealisedGainWithoutChangingTheBooks() {
            Investment investment = funded();

            investment.value(TREASURER, 5_300_000, TODAY, null, TODAY, NOW);
            investment.value(TREASURER, 5_100_000, TODAY.minusDays(1), "Older statement", TODAY, NOW);

            assertThat(investment.latestValuationMinor()).as("an older valuation does not replace a newer one")
                    .isEqualTo(5_300_000);
            assertThat(investment.unrealisedGainMinor()).isEqualTo(300_000);
            assertThat(investment.bookValueMinor()).isEqualTo(5_000_000);
            assertThat(investment.marketValueMinor()).isEqualTo(5_300_000);
            assertThatThrownBy(() -> investment.value(TREASURER, 1, TODAY.plusDays(1), null, TODAY, NOW))
                    .extracting("code").isEqualTo("INVESTMENT_INVALID");
        }
    }

    private static Investment propose(String name, Investment.Type type, long amount, LocalDate maturity) {
        return Investment.propose(CHAMA, TREASURER, name, type, "Sanlam", null, amount, "KES", 1_200, maturity, 2,
                TODAY, NOW);
    }

    private static Investment proposed() {
        return propose("Money market fund", Investment.Type.MONEY_MARKET_FUND, 5_000_000, null);
    }

    private static void approve(Investment investment) {
        investment.approve(CHAIR, null, NOW);
        investment.approve(SECRETARY, null, NOW);
    }

    private static Investment funded() {
        Investment investment = proposed();
        approve(investment);
        investment.fund(TREASURER, "CASH", null, TODAY.minusDays(30), TODAY, NOW);
        return investment;
    }
}
