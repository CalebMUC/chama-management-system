package ke.chama.modules.contribution.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContributionPaymentTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 6);
    private static final Instant NOW = Instant.parse("2026-11-06T09:00:00Z");

    @Test
    void mpesaCodesAreStoredInCapitals() {
        ContributionPayment payment = record(PaymentMethod.MPESA, " qk7h2xy9zb ", TODAY);

        assertThat(payment.reference()).isEqualTo("QK7H2XY9ZB");
        assertThat(payment.isRecorded()).isTrue();
    }

    @Test
    void mpesaAndBankPaymentsNeedAReference() {
        assertThatThrownBy(() -> record(PaymentMethod.MPESA, null, TODAY))
                .extracting("code").isEqualTo("CONTRIBUTION_INVALID_PAYMENT");
        assertThatThrownBy(() -> record(PaymentMethod.BANK, "  ", TODAY))
                .extracting("code").isEqualTo("CONTRIBUTION_INVALID_PAYMENT");
        assertThat(record(PaymentMethod.CASH, null, TODAY).reference()).isNull();
    }

    @Test
    void paymentsCannotBeDatedInTheFuture() {
        assertThatThrownBy(() -> record(PaymentMethod.CASH, null, TODAY.plusDays(1)))
                .extracting("code").isEqualTo("CONTRIBUTION_INVALID_PAYMENT");
    }

    @Test
    void aPaymentIsReversedOnceWithAReason() {
        ContributionPayment payment = record(PaymentMethod.CASH, null, TODAY);

        assertThatThrownBy(() -> payment.reverse(UUID.randomUUID(), " ", UUID.randomUUID(), NOW))
                .extracting("code").isEqualTo("CONTRIBUTION_INVALID_PAYMENT");
        payment.reverse(UUID.randomUUID(), "Wrong member", UUID.randomUUID(), NOW);
        assertThat(payment.status()).isEqualTo(ContributionPayment.Status.REVERSED);
        assertThatThrownBy(() -> payment.reverse(UUID.randomUUID(), "Again", UUID.randomUUID(), NOW))
                .extracting("code").isEqualTo("CONTRIBUTION_ALREADY_REVERSED");
    }

    private static ContributionPayment record(PaymentMethod method, String reference, LocalDate paidOn) {
        return ContributionPayment.record(UUID.randomUUID(), UUID.randomUUID(), 500_000, "KES", method, reference,
                paidOn, null, UUID.randomUUID(), TODAY, NOW);
    }
}
