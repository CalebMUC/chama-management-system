package ke.chama.modules.loan.domain.model;

import ke.chama.modules.chama.api.LoanRules;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoanProductTest {

    private static final UUID CHAMA = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-07T08:00:00Z");
    private static final LoanRules SETTINGS = new LoanRules("KES", 3, 1_000, 3, 5_000_000, 2, 2);

    @Test
    void theDefaultProductIsTheChamasLoanSettings() {
        LoanProduct normal = LoanProduct.defaultFrom(CHAMA, SETTINGS, NOW);

        assertThat(normal.code()).isEqualTo("NORMAL");
        assertThat(normal.maxMultiplier()).isEqualTo(3);
        assertThat(normal.interestBasisPoints()).isEqualTo(1_000);
        assertThat(normal.maxTermMonths()).isEqualTo(Loan.MAX_TERM_MONTHS);
        assertThat(normal.followsSettings(SETTINGS)).isTrue();
        assertThat(normal.rules("KES", 2)).isEqualTo(SETTINGS);

        LoanRules changed = new LoanRules("KES", 4, 1_200, 6, 10_000_000, 1, 2);
        LoanProduct followed = normal.followSettings(changed, NOW);
        assertThat(followed.followsSettings(changed)).isTrue();
        assertThat(followed.maxMultiplier()).isEqualTo(4);
    }

    @Test
    void theDefaultProductCanOnlyBeRenamedHere() {
        LoanProduct normal = LoanProduct.defaultFrom(CHAMA, SETTINGS, NOW);

        assertThat(normal.update(terms("Development loan", 1_000, 36, 3), NOW).name()).isEqualTo("Development loan");
        assertThatThrownBy(() -> normal.update(terms("Normal loan", 500, 36, 3), NOW))
                .extracting("code").isEqualTo("LOAN_PRODUCT_DEFAULT_RULE");
        assertThatThrownBy(() -> normal.withActive(false, NOW)).hasMessageContaining("cannot be deactivated");
    }

    @Test
    void otherProductsHaveTheirOwnTerms() {
        LoanProduct emergency = LoanProduct.create(CHAMA, "emerg", terms("Emergency loan", 500, 3, 1), null, NOW);

        assertThat(emergency.code()).isEqualTo("EMERG");
        assertThat(emergency.update(terms("Emergency loan", 600, 6, 2), NOW).maxTermMonths()).isEqualTo(6);
        assertThat(emergency.withActive(false, NOW).active()).isFalse();

        assertThatThrownBy(() -> LoanProduct.create(CHAMA, "X", terms("Bad", 500, 3, 1), null, NOW))
                .extracting("code").isEqualTo("LOAN_PRODUCT_INVALID");
        assertThatThrownBy(() -> LoanProduct.create(CHAMA, "BAD", terms("Bad", 500, 37, 1), null, NOW))
                .hasMessageContaining("longest term");
        assertThatThrownBy(() -> LoanProduct.create(CHAMA, "BAD", terms("Bad", 500, 3, 11), null, NOW))
                .hasMessageContaining("multiplier");
    }

    private static LoanProduct.Terms terms(String name, int interestBp, int maxTerm, int multiplier) {
        return new LoanProduct.Terms(name, interestBp, maxTerm, multiplier, 3, 5_000_000, 2);
    }
}
