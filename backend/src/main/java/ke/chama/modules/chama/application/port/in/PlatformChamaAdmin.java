package ke.chama.modules.chama.application.port.in;

import ke.chama.modules.chama.domain.model.LicensePlan;
import ke.chama.modules.chama.domain.model.LicenseStatus;
import ke.chama.shared.tenancy.LicenseFeature;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** For platform administrators: every chama on the platform and its licence. */
public interface PlatformChamaAdmin {

    Page<PlatformChamaView> chamas(Pageable pageable);

    PlatformChamaView chama(UUID chamaId);

    /** Renew, upgrade, suspend or reactivate. Audit-logged in the chama's own log. */
    LicenseView updateLicense(UUID chamaId, UUID actorUserId, LicenseChange change);

    record PlatformChamaView(ChamaView chama, LicenseView license) {
    }

    /** Null fields are left unchanged. */
    record LicenseChange(LicensePlan plan,
                         Instant validUntil,
                         Integer maxMembers,
                         Set<LicenseFeature> features,
                         LicenseStatus status,
                         String notes) {
    }
}
