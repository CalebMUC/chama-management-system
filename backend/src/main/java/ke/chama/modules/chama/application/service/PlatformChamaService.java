package ke.chama.modules.chama.application.service;

import ke.chama.modules.audit.api.AuditEntry;
import ke.chama.modules.audit.api.AuditTrail;
import ke.chama.modules.chama.application.port.in.ChamaView;
import ke.chama.modules.chama.application.port.in.LicenseView;
import ke.chama.modules.chama.application.port.in.PlatformChamaAdmin;
import ke.chama.modules.chama.application.port.out.ChamaRepository;
import ke.chama.modules.chama.application.port.out.LicenseRepository;
import ke.chama.modules.chama.domain.exception.ChamaNotFoundException;
import ke.chama.modules.chama.domain.exception.LicenseExceptions.LicenseNotFoundException;
import ke.chama.modules.chama.domain.model.Chama;
import ke.chama.modules.chama.domain.model.License;
import ke.chama.shared.tenancy.TenantContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Platform administration across chamas. Not {@code @Transactional}: reads run in system mode
 * (all tenants visible); a licence change runs as the chama it changes, so it is written, and
 * audit-logged, inside that chama's own data.
 */
@Service
class PlatformChamaService implements PlatformChamaAdmin {

    private final ChamaRepository chamas;
    private final LicenseRepository licenses;
    private final AuditTrail auditTrail;
    private final LicenseCache licenseCache;
    private final Clock clock;
    private final TransactionTemplate readOnly;
    private final TransactionTemplate readWrite;

    PlatformChamaService(ChamaRepository chamas, LicenseRepository licenses, AuditTrail auditTrail,
                         LicenseCache licenseCache, Clock clock, PlatformTransactionManager transactionManager) {
        this.chamas = chamas;
        this.licenses = licenses;
        this.auditTrail = auditTrail;
        this.licenseCache = licenseCache;
        this.clock = clock;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.readWrite = new TransactionTemplate(transactionManager);
    }

    @Override
    public Page<PlatformChamaView> chamas(Pageable pageable) {
        return TenantContext.callAsSystem(() -> readOnly.execute(status -> {
            Instant now = clock.instant();
            Page<Chama> page = chamas.findAll(pageable);
            Map<UUID, License> byChama = licenses.findByChamaIdIn(page.map(Chama::getId).getContent()).stream()
                    .collect(Collectors.toMap(License::getChamaId, Function.identity()));
            return page.map(chama -> view(chama, byChama.get(chama.getId()), now));
        }));
    }

    @Override
    public PlatformChamaView chama(UUID chamaId) {
        return TenantContext.callAs(chamaId, null, () -> readOnly.execute(status -> {
            Chama chama = chamas.findById(chamaId).orElseThrow(() -> new ChamaNotFoundException(chamaId));
            return view(chama, licenses.findByChamaId(chamaId).orElse(null), clock.instant());
        }));
    }

    @Override
    public LicenseView updateLicense(UUID chamaId, UUID actorUserId, LicenseChange change) {
        LicenseView updated = TenantContext.callAs(chamaId, null, () -> readWrite.execute(status -> {
            Instant now = clock.instant();
            chamas.findById(chamaId).orElseThrow(() -> new ChamaNotFoundException(chamaId));
            License license = licenses.findByChamaId(chamaId).orElseThrow(LicenseNotFoundException::new);
            Map<String, Object> before = license.asMap();
            license.update(change.plan(), change.validUntil(), change.maxMembers(), change.features(),
                    change.status(), change.notes(), actorUserId, now);
            licenses.save(license);
            auditTrail.record(AuditEntry.of(chamaId, actorUserId, "LICENSE_UPDATED", "LICENSE", license.getId())
                    .with(Map.of("before", before, "after", license.asMap())));
            return LicenseView.of(license, now);
        }));
        licenseCache.evict(chamaId);
        return updated;
    }

    private static PlatformChamaView view(Chama chama, License license, Instant now) {
        return new PlatformChamaView(ChamaView.from(chama), license == null ? null : LicenseView.of(license, now));
    }
}
