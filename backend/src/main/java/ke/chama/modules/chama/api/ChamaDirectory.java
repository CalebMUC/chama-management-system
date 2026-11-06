package ke.chama.modules.chama.api;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Read-only chama lookup for other modules. */
public interface ChamaDirectory {

    Optional<ChamaSummary> find(UUID chamaId);

    /** Unknown IDs are simply absent from the map. */
    Map<UUID, ChamaSummary> findAll(Collection<UUID> chamaIds);
}
