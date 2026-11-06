package ke.chama.modules.chama.api;

import java.util.List;
import java.util.UUID;

/**
 * Every chama on the platform, for background jobs that visit each chama in turn (each then
 * runs as that chama). Not for request handling: a request only ever sees its own chama.
 */
public interface ChamaRegistry {

    /** Chamas that are not archived. */
    List<UUID> activeChamaIds();
}
