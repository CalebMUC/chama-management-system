package ke.chama.modules.chama.application.port.in;

import ke.chama.modules.chama.domain.model.License;
import ke.chama.modules.chama.domain.model.LicensePlan;
import ke.chama.modules.chama.domain.model.LicenseState;
import ke.chama.shared.tenancy.LicenseFeature;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * @param state         whether the chama can operate now (ACTIVE, EXPIRED or SUSPENDED)
 * @param daysRemaining whole days until expiry; 0 once expired
 */
public record LicenseView(String licenseKey,
                          LicensePlan plan,
                          LicenseState state,
                          Instant validFrom,
                          Instant validUntil,
                          long daysRemaining,
                          int maxMembers,
                          Set<LicenseFeature> features,
                          String notes) {

    public static LicenseView of(License license, Instant now) {
        long days = now.isBefore(license.getValidUntil()) ? Duration.between(now, license.getValidUntil()).toDays() : 0;
        return new LicenseView(license.getLicenseKey(), license.getPlan(), license.stateAt(now),
                license.getValidFrom(), license.getValidUntil(), days, license.getMaxMembers(),
                Set.copyOf(license.getFeatures()), license.getNotes());
    }
}
