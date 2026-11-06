package ke.chama.modules.auth.infrastructure.persistence;

import ke.chama.modules.auth.application.port.out.UserRepository;
import ke.chama.modules.auth.domain.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Spring Data implements the {@link UserRepository} port directly: the method names already
 * match derived queries, so no hand-written adapter class is needed.
 */
interface JpaUserRepository extends JpaRepository<User, UUID>, UserRepository {
}
