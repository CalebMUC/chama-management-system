package ke.chama.modules.expense.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExpenseTest {

    private static final UUID CHAMA = UUID.randomUUID();
    private static final UUID TREASURER = UUID.randomUUID();
    private static final UUID CHAIR = UUID.randomUUID();
    private static final UUID SECRETARY = UUID.randomUUID();
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 6);
    private static final Instant NOW = Instant.parse("2026-11-06T09:00:00Z");

    @Test
    void anExpenseMustMakeSense() {
        assertThatThrownBy(() -> request(0, "Hall", "Hire", null)).extracting("code").isEqualTo("EXPENSE_INVALID");
        assertThatThrownBy(() -> request(100, " ", "Hire", null)).hasMessage("A payee is required");
        assertThatThrownBy(() -> request(100, "Hall", "", null)).hasMessage("A description is required");
        assertThatThrownBy(() -> request(100, "Hall", "Hire", TODAY.plusDays(1))).extracting("code")
                .isEqualTo("EXPENSE_INVALID");

        Expense expense = request(300_000, " Community Hall ", " Hall hire ", null);
        assertThat(expense.payee()).isEqualTo("Community Hall");
        assertThat(expense.incurredOn()).isEqualTo(TODAY);
        assertThat(expense.status()).isEqualTo(Expense.Status.PENDING_APPROVAL);
    }

    @Test
    void aQuorumOfOfficialsOtherThanTheRequesterApproves() {
        Expense expense = request(300_000, "Hall", "Hire", null);

        assertThatThrownBy(() -> expense.approve(TREASURER, null, NOW)).extracting("code")
                .isEqualTo("EXPENSE_SELF_APPROVAL");
        expense.approve(CHAIR, "OK", NOW);
        assertThatThrownBy(() -> expense.approve(CHAIR, null, NOW)).extracting("code")
                .isEqualTo("EXPENSE_ALREADY_APPROVED");
        assertThat(expense.status()).isEqualTo(Expense.Status.PENDING_APPROVAL);
        expense.approve(SECRETARY, null, NOW);

        assertThat(expense.status()).isEqualTo(Expense.Status.APPROVED);
        assertThat(expense.approvals()).extracting(Expense.Approval::approverUserId).containsExactly(CHAIR, SECRETARY);
    }

    @Test
    void onlyAnApprovedExpenseIsPaidAndOnlyOnce() {
        Expense expense = request(300_000, "Hall", "Hire", null);
        assertThatThrownBy(() -> expense.pay(TREASURER, "CASH", null, null, TODAY)).extracting("code")
                .isEqualTo("EXPENSE_WRONG_STATUS");
        expense.approve(CHAIR, null, NOW);
        expense.approve(SECRETARY, null, NOW);

        assertThatThrownBy(() -> expense.pay(TREASURER, "MPESA", null, null, TODAY)).hasMessageContaining("reference");
        expense.pay(TREASURER, "MPESA", " qkhall01 ", null, TODAY);

        assertThat(expense.status()).isEqualTo(Expense.Status.PAID);
        assertThat(expense.paymentReference()).isEqualTo("QKHALL01");
        assertThat(expense.paidOn()).isEqualTo(TODAY);
        assertThatThrownBy(() -> expense.pay(TREASURER, "CASH", null, null, TODAY)).extracting("code")
                .isEqualTo("EXPENSE_WRONG_STATUS");
        assertThatThrownBy(() -> expense.cancel(TREASURER, NOW)).extracting("code").isEqualTo("EXPENSE_WRONG_STATUS");
    }

    @Test
    void rejectingNeedsAReasonAndCancellingIsForTheRequester() {
        Expense rejected = request(300_000, "Hall", "Hire", null);
        assertThatThrownBy(() -> rejected.reject(TREASURER, "No", NOW)).extracting("code").isEqualTo("EXPENSE_SELF_APPROVAL");
        assertThatThrownBy(() -> rejected.reject(CHAIR, " ", NOW)).hasMessage("A reason is required");
        rejected.reject(CHAIR, "Too expensive", NOW);
        assertThat(rejected.status()).isEqualTo(Expense.Status.REJECTED);
        assertThat(rejected.rejectionReason()).isEqualTo("Too expensive");

        Expense cancelled = request(300_000, "Hall", "Hire", null);
        assertThatThrownBy(() -> cancelled.cancel(CHAIR, NOW)).extracting("code").isEqualTo("EXPENSE_NOT_REQUESTER");
        cancelled.cancel(TREASURER, NOW);
        assertThat(cancelled.status()).isEqualTo(Expense.Status.CANCELLED);
    }

    private static Expense request(long amount, String payee, String description, LocalDate incurredOn) {
        return Expense.request(CHAMA, TREASURER, amount, "KES", payee, description, "5000", null, incurredOn, 2, TODAY, NOW);
    }
}
