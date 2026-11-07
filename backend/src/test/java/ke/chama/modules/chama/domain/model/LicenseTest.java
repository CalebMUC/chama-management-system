package ke.chama.modules.chama.domain.model;

import ke.chama.shared.domain.exception.DomainValidationException;
import ke.chama.shared.tenancy.LicenseFeature;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LicenseTest {

    private static final Instant NOW = Instant.parse("2026-11-06T09:00:00Z");
    private static final UUID CHAMA = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();

    @Test
    void aNewChamaGetsATrialWithEveryFeature() {
        License trial = License.trial(CHAMA, NOW, Duration.ofDays(30));

        assertThat(trial.getPlan()).isEqualTo(LicensePlan.TRIAL);
        assertThat(trial.getValidUntil()).isEqualTo(NOW.plus(Duration.ofDays(30)));
        assertThat(trial.getFeatures()).containsExactlyInAnyOrder(LicenseFeature.values());
        assertThat(trial.getMaxMembers()).isEqualTo(LicensePlan.TRIAL.defaultMaxMembers());
        assertThat(trial.getLicenseKey()).matches("CHM(-[A-HJ-NP-Z2-9]{4}){4}");
        assertThat(trial.stateAt(NOW)).isEqualTo(LicenseState.ACTIVE);
    }

    @Test
    void expiresOnItsOwnAtValidUntil() {
        License trial = License.trial(CHAMA, NOW, Duration.ofDays(30));

        assertThat(trial.stateAt(NOW.plus(Duration.ofDays(30)).minusSeconds(1))).isEqualTo(LicenseState.ACTIVE);
        assertThat(trial.stateAt(NOW.plus(Duration.ofDays(30)))).isEqualTo(LicenseState.EXPIRED);
    }

    @Test
    void suspensionWinsOverValidity() {
        License licence = License.trial(CHAMA, NOW, Duration.ofDays(30));

        licence.update(null, null, null, null, LicenseStatus.SUSPENDED, "Invoice unpaid", ADMIN, NOW);

        assertThat(licence.stateAt(NOW)).isEqualTo(LicenseState.SUSPENDED);
        assertThat(licence.getNotes()).isEqualTo("Invoice unpaid");
        assertThat(licence.getUpdatedBy()).isEqualTo(ADMIN);
    }

    @Test
    void changingThePlanAppliesItsDefaultsUnlessOverridden() {
        License licence = License.trial(CHAMA, NOW, Duration.ofDays(30));

        licence.update(LicensePlan.BASIC, null, null, null, null, null, ADMIN, NOW);
        assertThat(licence.getMaxMembers()).isEqualTo(LicensePlan.BASIC.defaultMaxMembers());
        assertThat(licence.getFeatures()).doesNotContain(LicenseFeature.LOANS);

        licence.update(LicensePlan.STANDARD, null, 250, Set.of(LicenseFeature.LOANS), null, null, ADMIN, NOW);
        assertThat(licence.getMaxMembers()).isEqualTo(250);
        assertThat(licence.getFeatures()).containsExactly(LicenseFeature.LOANS);
    }

    @Test
    void rejectsAValidityPeriodThatEndsBeforeItStarts() {
        License licence = License.trial(CHAMA, NOW, Duration.ofDays(30));

        assertThatThrownBy(() -> licence.update(null, NOW.minusSeconds(1), null, null, null, null, ADMIN, NOW))
                .isInstanceOf(DomainValidationException.class)
                .extracting("code").isEqualTo("LICENSE_INVALID_PERIOD");
    }
}
