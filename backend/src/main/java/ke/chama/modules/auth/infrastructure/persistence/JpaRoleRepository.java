package ke.chama.modules.auth.infrastructure.persistence;

import ke.chama.modules.auth.application.port.out.RoleRepository;
import ke.chama.modules.auth.domain.model.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface JpaRoleRepository extends JpaRepository<Role, UUID>, RoleRepository {
}
