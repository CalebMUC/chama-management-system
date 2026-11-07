package ke.chama.modules.chama.application.port.out;

import ke.chama.modules.chama.domain.model.License;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LicenseRepository {

    License save(License license);

    Optional<License> findByChamaId(UUID chamaId);

    /** Only meaningful in system mode (platform administration). */
    List<License> findByChamaIdIn(Collection<UUID> chamaIds);
}
