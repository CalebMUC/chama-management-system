package ke.chama.modules.auth.application.port.out;

import ke.chama.modules.auth.domain.model.User;

import java.util.Optional;
import java.util.UUID;

/** Persistence port. Implemented by Spring Data in infrastructure.persistence. */
public interface UserRepository {

    Optional<User> findById(UUID id);

    /** Expects an already-normalized (lower-case) email. */
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    User save(User user);
}
