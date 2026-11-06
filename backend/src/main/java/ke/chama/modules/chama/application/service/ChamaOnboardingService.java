package ke.chama.modules.chama.application.service;

import org.springframework.security.access.AccessDeniedException;
import ke.chama.modules.chama.application.port.in.PlatformChamaAdmin;
import ke.chama.modules.audit.api.AuditEntry;
import ke.chama.modules.audit.api.AuditTrail;
import ke.chama.modules.auth.api.UserProvisioning;
import ke.chama.modules.auth.api.UserSummary;
import ke.chama.modules.chama.application.LicensingProperties;
import ke.chama.modules.chama.application.port.in.ChamaOnboarding;
import ke.chama.modules.chama.application.port.in.ChamaView;
import ke.chama.modules.chama.application.port.in.LicenseView;
import ke.chama.modules.chama.application.port.out.ChamaRepository;
import ke.chama.modules.chama.application.port.out.LicenseRepository;
import ke.chama.modules.chama.domain.exception.LicenseExceptions.ChamaCodeTakenException;
import ke.chama.modules.chama.domain.model.Chama;
import ke.chama.modules.chama.domain.model.License;
import ke.chama.shared.security.AuthenticatedUser;
import ke.chama.shared.security.CurrentUser;
import ke.chama.shared.tenancy.TenantContext;
import ke.chama.shared.tenancy.TenantResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Onboards a chama. Deliberately not {@code @Transactional}: the new tenant's id is chosen
 * first and the work then runs <em>as that tenant</em>, so every row is written with the new
 * {@code tenant_id} and row-level security accepts it.
 * <p>
 * In one transaction: the administrator's account, the chama, its trial licence, and (through
 * {@code ChamaCreated}, handled synchronously by the member module) the administrator's active
 * chairperson membership and the default role permissions.
 */
@Service
class ChamaOnboardingService implements ChamaOnboarding {

    private static final Logger log = LoggerFactory.getLogger(ChamaOnboardingService.class);

    private final ChamaRepository chamas;
    private final LicenseRepository licenses;
    private final UserProvisioning userProvisioning;
    private final TenantResolver tenantResolver;
    private final AuditTrail auditTrail;
    private final ApplicationEventPublisher events;
    private final LicensingProperties properties;
    private final Clock clock;
    private final TransactionTemplate transaction;

    ChamaOnboardingService(ChamaRepository chamas, LicenseRepository licenses, UserProvisioning userProvisioning,
                           TenantResolver tenantResolver, AuditTrail auditTrail, ApplicationEventPublisher events,
                           LicensingProperties properties, Clock clock, PlatformTransactionManager transactionManager) {
        this.chamas = chamas;
        this.licenses = licenses;
        this.userProvisioning = userProvisioning;
        this.tenantResolver = tenantResolver;
        this.auditTrail = auditTrail;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Override
    public OnboardingResult onboard(Command command) {
        // Only platform administrators add chamas (also enforced on /api/v1/platform/** in SecurityConfig).
        boolean platformAdmin = CurrentUser.find()
                .filter(user -> user.hasPlatformRole("ROLE_ADMIN"))
                .isPresent();
        if (!platformAdmin) {
            throw new AccessDeniedException("Only platform administrators can onboard a chama");
        }
        String code = Chama.normalizeCode(command.code());
        if (tenantResolver.findIdByCode(code).isPresent()) {
            throw new ChamaCodeTakenException(code);
        }
        UUID onboardedBy = CurrentUser.find().map(AuthenticatedUser::userId).orElse(null);

        UUID chamaId = UUID.randomUUID();
        OnboardingResult result = TenantContext.callAs(chamaId, code,
                () -> transaction.execute(status -> create(chamaId, code, command, onboardedBy)));
        log.info("Onboarded chama {} ({})", code, chamaId);
        return result;
    }

    private OnboardingResult create(UUID chamaId, String code, Command command, UUID onboardedBy) {
        Instant now = clock.instant();
        Admin admin = command.admin();
        UserSummary account = userProvisioning.createAccount(new UserProvisioning.NewAccount(
                admin.email(), admin.phoneNumber(), admin.password(), admin.firstName(), admin.lastName()));

        Chama chama = Chama.found(chamaId, code, command.name(), command.description(), command.currency(),
                command.settings(), account.id(), account.email(), now);
        chamas.save(chama);
        License license = License.trial(chamaId, now, properties.trialLength());
        PlatformChamaAdmin.LicenseChange terms = command.license();
        if (terms != null) {
            license.update(terms.plan(), terms.validUntil(), terms.maxMembers(), terms.features(), terms.status(),
                    terms.notes(), onboardedBy, now);
        }
        license = licenses.save(license);

        auditTrail.record(AuditEntry.of(chamaId, account.id(), "CHAMA_CREATED", ChamaService.TARGET, chamaId)
                .with(Map.of("name", chama.getName(), "code", code, "currency", chama.getCurrency().getCurrencyCode(),
                        "settings", command.settings().asMap())));
        auditTrail.record(AuditEntry.of(chamaId, onboardedBy, "LICENSE_ISSUED", "LICENSE", license.getId())
                .with(license.asMap()));

        // Handled synchronously by the member module: the administrator becomes the active chairperson.
        chama.pullDomainEvents().forEach(events::publishEvent);
        return new OnboardingResult(ChamaView.from(chama), LicenseView.of(license, now), account.id(), account.email());
    }
}
