package ke.chama.modules.chama.api;

import ke.chama.shared.tenancy.LicenseFeature;

import java.time.Instant;
import java.util.Set;

/**
 * What other modules may know about a chama's licence.
 *
 * @param state ACTIVE, EXPIRED or SUSPENDED; only ACTIVE allows the chama to operate
 */
public record LicenseSummary(String plan,
                             String state,
                             Instant validUntil,
                             int maxMembers,
                             Set<LicenseFeature> features) {

    public static final String ACTIVE = "ACTIVE";

    public boolean usable() {
        return ACTIVE.equals(state);
    }

    public boolean includes(LicenseFeature feature) {
        return features.contains(feature);
    }
}
