package ke.chama.modules.chama.domain.model;

import ke.chama.shared.tenancy.LicenseFeature;

import java.util.EnumSet;
import java.util.Set;

import static ke.chama.shared.tenancy.LicenseFeature.*;

/**
 * Licence tiers and what each includes by default. A platform administrator can still override
 * a chama's member limit and features individually.
 */
public enum LicensePlan {

    /** Everything, for a limited time, so a new chama can try the whole product. */
    TRIAL(25, EnumSet.allOf(LicenseFeature.class)),
    BASIC(30, EnumSet.of(CONTRIBUTIONS, PENALTIES, EXPENSES, REPORTS)),
    STANDARD(100, EnumSet.of(CONTRIBUTIONS, PENALTIES, EXPENSES, REPORTS, LOANS, INVESTMENTS, SMS_NOTIFICATIONS)),
    PREMIUM(1000, EnumSet.allOf(LicenseFeature.class));

    private final int defaultMaxMembers;
    private final Set<LicenseFeature> defaultFeatures;

    LicensePlan(int defaultMaxMembers, Set<LicenseFeature> defaultFeatures) {
        this.defaultMaxMembers = defaultMaxMembers;
        this.defaultFeatures = defaultFeatures;
    }

    public int defaultMaxMembers() {
        return defaultMaxMembers;
    }

    public Set<LicenseFeature> defaultFeatures() {
        return EnumSet.copyOf(defaultFeatures);
    }
}
