package ke.chama.modules.chama.application.port.in;

import ke.chama.modules.chama.domain.model.ChamaSettings;

import java.util.UUID;

/** Operations on the caller's own chama ({@code chamaId} is their tenant). */
public interface ChamaUseCases {

    ChamaView get(UUID chamaId);

    /** Recorded in the audit log with before/after values. */
    ChamaView changeSettings(UUID chamaId, UUID actorUserId, ChamaSettings settings);

    LicenseView currentLicense(UUID chamaId);
}
