package ke.chama.modules.loan.domain.model;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoanTest {

    private static final UUID CHAMA = UUID.randomUUID();
    private static final UUID BORROWER = UUID.randomUUID();
    private static final UUID CHAIR = UUID.randomUUID();
    private static final UUID TREASURER = UUID.randomUUID();
    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final UUID CAROL = UUID.randomUUID();
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 6);
    private static final Instant NOW = Instant.parse("2026-11-06T09:00:00Z");

    @Nested
    class Applying {

        @Test
        void interestIsFlatOnThePrincipal() {
            Loan loan = smallLoan(1_000_000, 10);

            assertThat(loan.interestMinor()).isEqualTo(100_000);       // 10% of KES 10,000
            assertThat(loan.totalDueMinor()).isEqualTo(1_100_000);
            assertThat(loan.status()).isEqualTo(Loan.Status.PENDING_APPROVAL);
            assertThat(loan.outstandingMinor()).as("nothing is owed before disbursement").isZero();
        }

        @Test
        void theLoanMustMakeSense() {
            assertThatThrownBy(() -> apply(0, 6, "School fees", 0, List.of())).extracting("code").isEqualTo("LOAN_INVALID");
            assertThatThrownBy(() -> apply(100, 0, "School fees", 0, List.of())).extracting("code").isEqualTo("LOAN_INVALID");
            assertThatThrownBy(() -> apply(100, 37, "School fees", 0, List.of())).extracting("code").isEqualTo("LOAN_INVALID");
            assertThatThrownBy(() -> apply(100, 6, " ", 0, List.of())).extracting("code").isEqualTo("LOAN_INVALID");
        }

        @Test
        void bigLoansNeedEnoughDistinctGuarantorsOtherThanTheBorrower() {
            assertThatThrownBy(() -> apply(100, 6, "Plot", 2, List.of(ALICE)))
                    .hasMessageContaining("needs 2 guarantors");
            assertThatThrownBy(() -> apply(100, 6, "Plot", 2, List.of(ALICE, ALICE)))
                    .hasMessageContaining("only once");
            assertThatThrownBy(() -> apply(100, 6, "Plot", 2, List.of(ALICE, BORROWER)))
                    .hasMessageContaining("own loan");
            assertThatThrownBy(() -> apply(100, 6, "Plot", 0, List.of(ALICE)))
                    .hasMessageContaining("does not need guarantors");

            assertThat(apply(100, 6, "Plot", 2, List.of(ALICE, BOB)).status()).isEqualTo(Loan.Status.PENDING_GUARANTORS);
        }
    }

    @Nested
    class Guarantors {

        @Test
        void theLoanGoesForApprovalOnceEnoughAccept() {
            Loan loan = apply(100, 6, "Plot", 2, List.of(ALICE, BOB, CAROL));

            loan.respondAsGuarantor(ALICE, true, NOW);
            loan.respondAsGuarantor(BOB, false, NOW);
            assertThat(loan.status()).isEqualTo(Loan.Status.PENDING_GUARANTORS);
            loan.respondAsGuarantor(CAROL, true, NOW);

            assertThat(loan.status()).isEqualTo(Loan.Status.PENDING_APPROVAL);
            assertThat(loan.acceptedGuarantors()).isEqualTo(2);
        }

        @Test
        void requestsStillOpenAreWithdrawnWhenNoLongerNeeded() {
            Loan loan = apply(100, 6, "Plot", 1, List.of(ALICE, BOB));

            loan.respondAsGuarantor(ALICE, true, NOW);

            assertThat(loan.guarantor(BOB).orElseThrow().status()).isEqualTo(Loan.Guarantor.Status.WITHDRAWN);
            assertThatThrownBy(() -> loan.respondAsGuarantor(BOB, true, NOW)).extracting("code")
                    .isEqualTo("LOAN_GUARANTOR_RESPONDED");
        }

        @Test
        void onlyTheBorrowerAddsGuarantorsAndOnlyThoseAskedRespond() {
            Loan loan = apply(100, 6, "Plot", 2, List.of(ALICE, BOB));

            assertThatThrownBy(() -> loan.addGuarantor(ALICE, CAROL, NOW)).extracting("code").isEqualTo("LOAN_NOT_BORROWER");
            assertThatThrownBy(() -> loan.respondAsGuarantor(CAROL, true, NOW)).extracting("code").isEqualTo("LOAN_NOT_GUARANTOR");
            loan.respondAsGuarantor(BOB, false, NOW);
            loan.addGuarantor(BORROWER, CAROL, NOW);
            assertThat(loan.guarantors()).hasSize(3);
        }
    }

    @Nested
    class Approving {

        @Test
        void aQuorumOfDistinctOfficialsApproves() {
            Loan loan = smallLoan(1_000_000, 10);

            loan.approve(CHAIR, "Fine", NOW);
            assertThat(loan.status()).isEqualTo(Loan.Status.PENDING_APPROVAL);
            assertThatThrownBy(() -> loan.approve(CHAIR, null, NOW)).extracting("code").isEqualTo("LOAN_ALREADY_APPROVED");
            loan.approve(TREASURER, null, NOW);

            assertThat(loan.status()).isEqualTo(Loan.Status.APPROVED);
        }

        @Test
        void neitherTheBorrowerNorAGuarantorApproves() {
            Loan loan = apply(100, 6, "Plot", 1, List.of(ALICE));
            loan.respondAsGuarantor(ALICE, true, NOW);

            assertThatThrownBy(() -> loan.approve(BORROWER, null, NOW)).extracting("code").isEqualTo("LOAN_SELF_HANDLING");
            assertThatThrownBy(() -> loan.approve(ALICE, null, NOW)).extracting("code")
                    .isEqualTo("LOAN_GUARANTOR_CANNOT_APPROVE");
        }

        @Test
        void rejectingNeedsAReasonAndClosesTheLoan() {
            Loan loan = apply(100, 6, "Plot", 1, List.of(ALICE));

            assertThatThrownBy(() -> loan.reject(CHAIR, "", NOW)).extracting("code").isEqualTo("LOAN_INVALID");
            loan.reject(CHAIR, "Savings too recent", NOW);

            assertThat(loan.status()).isEqualTo(Loan.Status.REJECTED);
            assertThat(loan.status().open()).isFalse();
            assertThat(loan.guarantor(ALICE).orElseThrow().status()).isEqualTo(Loan.Guarantor.Status.WITHDRAWN);
            assertThatThrownBy(() -> loan.cancel(BORROWER, NOW)).extracting("code").isEqualTo("LOAN_WRONG_STATUS");
        }
    }

    @Nested
    class Repaying {

        @Test
        void equalMonthlyInstalmentsStartAMonthAfterDisbursement() {
            Loan loan = disbursed(1_000_000, 3);   // KES 11,000 over 3 months

            List<Loan.Instalment> schedule = loan.schedule(TODAY);

            assertThat(schedule).extracting(Loan.Instalment::amountMinor).containsExactly(366_666L, 366_666L, 366_668L);
            assertThat(schedule).extracting(Loan.Instalment::dueDate)
                    .containsExactly(TODAY.plusMonths(1), TODAY.plusMonths(2), TODAY.plusMonths(3));
            assertThat(loan.maturityDate()).isEqualTo(TODAY.plusMonths(3));
            assertThat(loan.outstandingMinor()).isEqualTo(1_100_000);
        }

        @Test
        void repaymentsClearTheOldestInstalmentFirst() {
            Loan loan = disbursed(1_000_000, 2);   // two instalments of KES 5,500

            loan.repay(TREASURER, 600_000);

            List<Loan.Instalment> schedule = loan.schedule(TODAY);
            assertThat(schedule.get(0).status()).isEqualTo("PAID");
            assertThat(schedule.get(1).status()).isEqualTo("PARTLY_PAID");
            assertThat(schedule.get(1).paidMinor()).isEqualTo(50_000);
            assertThat(loan.nextInstalment(TODAY).orElseThrow().unpaidMinor()).isEqualTo(500_000);
        }

        @Test
        void unpaidInstalmentsPastTheirDueDateAreArrears() {
            Loan loan = disbursed(1_000_000, 2);

            assertThat(loan.arrearsMinor(TODAY.plusMonths(1))).as("due today is not yet late").isZero();
            assertThat(loan.arrearsMinor(TODAY.plusMonths(1).plusDays(1))).isEqualTo(550_000);
            assertThat(loan.schedule(TODAY.plusMonths(3)).get(1).status()).isEqualTo("OVERDUE");
        }

        @Test
        void theLastRepaymentClosesTheLoanAndNoMoreIsAccepted() {
            Loan loan = disbursed(1_000_000, 2);

            assertThatThrownBy(() -> loan.repay(TREASURER, 1_100_001)).extracting("code").isEqualTo("LOAN_INVALID");
            assertThatThrownBy(() -> loan.repay(BORROWER, 100)).extracting("code").isEqualTo("LOAN_SELF_HANDLING");
            loan.repay(TREASURER, 1_100_000);

            assertThat(loan.status()).isEqualTo(Loan.Status.REPAID);
            assertThat(loan.outstandingMinor()).isZero();
            assertThatThrownBy(() -> loan.repay(TREASURER, 1)).extracting("code").isEqualTo("LOAN_WRONG_STATUS");
        }

        @Test
        void disbursementNeedsApprovalAndAReferenceForMpesa() {
            Loan loan = smallLoan(1_000_000, 2);
            assertThatThrownBy(() -> loan.disburse(TREASURER, "CASH", null, null, TODAY)).extracting("code")
                    .isEqualTo("LOAN_WRONG_STATUS");
            loan.approve(CHAIR, null, NOW);
            loan.approve(TREASURER, null, NOW);

            assertThatThrownBy(() -> loan.disburse(TREASURER, "MPESA", " ", null, TODAY)).extracting("code")
                    .isEqualTo("LOAN_INVALID");
            assertThatThrownBy(() -> loan.disburse(TREASURER, "CASH", null, TODAY.plusDays(1), TODAY)).extracting("code")
                    .isEqualTo("LOAN_INVALID");
            assertThatThrownBy(() -> loan.disburse(BORROWER, "CASH", null, null, TODAY)).extracting("code")
                    .isEqualTo("LOAN_SELF_HANDLING");
        }
    }

    private static Loan apply(long principal, int term, String purpose, int guarantors, List<UUID> named) {
        return Loan.apply(CHAMA, BORROWER, principal, term, purpose, "KES", 1_000, guarantors, 2, named, TODAY, NOW);
    }

    private static Loan smallLoan(long principal, int term) {
        return apply(principal, term, "School fees", 0, List.of());
    }

    private static Loan disbursed(long principal, int term) {
        Loan loan = smallLoan(principal, term);
        loan.approve(CHAIR, null, NOW);
        loan.approve(TREASURER, null, NOW);
        loan.disburse(TREASURER, "MPESA", "qk7loan01", TODAY, TODAY);
        loan.disbursementPostedAs(UUID.randomUUID());
        assertThat(loan.disbursementReference()).isEqualTo("QK7LOAN01");
        return loan;
    }
}
