package ke.chama.modules.chama.application.port.out;

import ke.chama.modules.chama.domain.model.Chama;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChamaRepository {

    Optional<Chama> findById(UUID id);

    List<Chama> findAllById(Iterable<UUID> ids);

    Chama save(Chama chama);
}
