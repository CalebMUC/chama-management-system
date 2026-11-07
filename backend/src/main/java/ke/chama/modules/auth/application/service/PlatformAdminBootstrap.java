package ke.chama.modules.auth.application.service;

import ke.chama.modules.auth.application.PlatformAdminProperties;
import ke.chama.modules.auth.application.port.out.PasswordHasher;
import ke.chama.modules.auth.application.port.out.RoleRepository;
import ke.chama.modules.auth.application.port.out.UserRepository;
import ke.chama.modules.auth.domain.model.Role;
import ke.chama.modules.auth.domain.model.User;
import ke.chama.shared.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;

/** Creates the configured platform administrator once, in the platform tenant. */
@Component
class PlatformAdminBootstrap {

    private static final Logger log = LoggerFactory.getLogger(PlatformAdminBootstrap.class);

    private final PlatformAdminProperties properties;
    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordHasher passwordHasher;
    private final Clock clock;
    private final TransactionTemplate transaction;

    PlatformAdminBootstrap(PlatformAdminProperties properties, UserRepository users, RoleRepository roles,
                           PasswordHasher passwordHasher, Clock clock, PlatformTransactionManager transactionManager) {
        this.properties = properties;
        this.users = users;
        this.roles = roles;
        this.passwordHasher = passwordHasher;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @EventListener(ApplicationReadyEvent.class)
    void ensurePlatformAdmin() {
        if (!properties.configured()) {
            return;
        }
        TenantContext.runAs(TenantContext.PLATFORM, null, () -> transaction.executeWithoutResult(status -> create()));
    }

    private void create() {
        String email = User.normalizeEmail(properties.email());
        if (users.existsByEmail(email)) {
            return;
        }
        PasswordPolicy.requireAcceptable(properties.password());
        Instant now = clock.instant();
        Role userRole = roles.findByName(Role.USER).orElseThrow();
        Role adminRole = roles.findByName(Role.ADMIN)
                .orElseThrow(() -> new IllegalStateException(Role.ADMIN + " is missing; check the auth migrations"));

        User admin = User.register(TenantContext.PLATFORM, email, properties.phoneNumber(),
                passwordHasher.hash(properties.password()), properties.firstName(), properties.lastName(),
                userRole, false, now);
        admin.addRole(adminRole);
        admin.markEmailVerified(now);
        admin.pullDomainEvents();   // nothing to notify: no chama membership, no verification email
        users.save(admin);
        log.info("Created platform administrator {}", email);
    }
}
