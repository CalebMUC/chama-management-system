package ke.chama.modules.chama.application.port.out;

import ke.chama.modules.chama.domain.model.Chama;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.UUID;

/** Row-level security limits this to the current tenant's chama, except in system mode. */
public interface ChamaRepository {

    Optional<Chama> findById(UUID id);

    /** Every chama; only meaningful in system mode (platform administration). */
    Page<Chama> findAll(Pageable pageable);

    Chama save(Chama chama);
}
