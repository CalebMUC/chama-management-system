package ke.chama.modules.chama.infrastructure.persistence;

import ke.chama.modules.chama.application.port.out.LicenseRepository;
import ke.chama.modules.chama.domain.model.License;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface JpaLicenseRepository extends JpaRepository<License, UUID>, LicenseRepository {
}
