package ke.chama.modules.chama.infrastructure.persistence;

import ke.chama.modules.chama.application.port.out.ChamaRepository;
import ke.chama.modules.chama.domain.model.Chama;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface JpaChamaRepository extends JpaRepository<Chama, UUID>, ChamaRepository {
}
