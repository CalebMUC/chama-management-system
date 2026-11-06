package ke.chama.modules.chama.presentation.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import ke.chama.modules.chama.application.port.in.PlatformChamaAdmin.LicenseChange;
import ke.chama.modules.chama.domain.model.LicensePlan;
import ke.chama.modules.chama.domain.model.LicenseStatus;
import ke.chama.shared.tenancy.LicenseFeature;

import java.time.Instant;
import java.util.Set;

/**
 * Every field is optional; omitted fields stay as they are. Examples:
 * renew {@code {"validUntil": "2027-12-31T21:00:00Z"}}, upgrade {@code {"plan": "STANDARD"}},
 * suspend {@code {"status": "SUSPENDED", "notes": "Invoice 2026-11 unpaid"}}.
 * Changing {@code plan} resets the member limit and features to that plan's defaults unless
 * they are also given.
 */
public record UpdateLicenseRequest(LicensePlan plan,
                                   Instant validUntil,
                                   @Min(1) Integer maxMembers,
                                   Set<LicenseFeature> features,
                                   LicenseStatus status,
                                   @Size(max = 500) String notes) {

    public LicenseChange toChange() {
        return new LicenseChange(plan, validUntil, maxMembers, features, status, notes);
    }
}
