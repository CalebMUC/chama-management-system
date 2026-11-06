package ke.chama.modules.chama.application.port.in;

import ke.chama.modules.chama.domain.model.ChamaSettings;

import java.util.Currency;
import java.util.UUID;

public interface ChamaUseCases {

    /** The founder must have a verified email and becomes the chama's first chairperson. */
    ChamaView found(UUID founderUserId, String name, String description, Currency currency, ChamaSettings settings);

    ChamaView get(UUID chamaId);

    /** Recorded in the audit log with before/after values. */
    ChamaView changeSettings(UUID chamaId, UUID actorUserId, ChamaSettings settings);
}
