package ke.chama.modules.auth.application.port.out;

import ke.chama.modules.auth.domain.model.Role;

import java.util.Optional;

public interface RoleRepository {

    Optional<Role> findByName(String name);
}
