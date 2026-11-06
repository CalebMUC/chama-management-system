package ke.chama.modules.chama.application.service;

import ke.chama.modules.audit.api.AuditEntry;
import ke.chama.modules.audit.api.AuditTrail;
import ke.chama.modules.chama.api.ChamaDirectory;
import ke.chama.modules.chama.api.ChamaSummary;
import ke.chama.modules.chama.api.LicenseSummary;
import ke.chama.modules.chama.application.port.in.ChamaUseCases;
import ke.chama.modules.chama.application.port.in.ChamaView;
import ke.chama.modules.chama.application.port.in.LicenseView;
import ke.chama.modules.chama.application.port.out.ChamaRepository;
import ke.chama.modules.chama.application.port.out.LicenseRepository;
import ke.chama.modules.chama.domain.exception.ChamaNotFoundException;
import ke.chama.modules.chama.domain.exception.LicenseExceptions.LicenseNotFoundException;
import ke.chama.modules.chama.domain.model.Chama;
import ke.chama.modules.chama.domain.model.ChamaSettings;
import ke.chama.modules.chama.domain.model.License;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** The caller's own chama. Row-level security makes every other chama invisible here. */
@Service
@Transactional
public class ChamaService implements ChamaUseCases, ChamaDirectory {

    static final String TARGET = "CHAMA";

    private final ChamaRepository chamas;
    private final LicenseRepository licenses;
    private final AuditTrail auditTrail;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ChamaService(ChamaRepository chamas, LicenseRepository licenses, AuditTrail auditTrail,
                        ApplicationEventPublisher events, Clock clock) {
        this.chamas = chamas;
        this.licenses = licenses;
        this.auditTrail = auditTrail;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public ChamaView get(UUID chamaId) {
        return ChamaView.from(load(chamaId));
    }

    @Override
    public ChamaView changeSettings(UUID chamaId, UUID actorUserId, ChamaSettings settings) {
        Chama chama = load(chamaId);
        ChamaSettings before = chama.getSettings();
        chama.changeSettings(settings, actorUserId, clock.instant());
        auditTrail.record(AuditEntry.of(chamaId, actorUserId, "CHAMA_SETTINGS_CHANGED", TARGET, chamaId)
                .with(Map.of("before", before.asMap(), "after", settings.asMap())));
        chama.pullDomainEvents().forEach(events::publishEvent);
        return ChamaView.from(chama);
    }

    @Override
    @Transactional(readOnly = true)
    public LicenseView currentLicense(UUID chamaId) {
        License license = licenses.findByChamaId(chamaId).orElseThrow(LicenseNotFoundException::new);
        return LicenseView.of(license, clock.instant());
    }

    // ---- ChamaDirectory (for other modules) -------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Optional<ChamaSummary> find(UUID chamaId) {
        return chamas.findById(chamaId).map(c ->
                new ChamaSummary(c.getId(), c.getCode(), c.getName(), c.getCurrency().getCurrencyCode(), c.isActive()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LicenseSummary> license(UUID chamaId) {
        Instant now = clock.instant();
        return licenses.findByChamaId(chamaId).map(l -> new LicenseSummary(l.getPlan().name(),
                l.stateAt(now).name(), l.getValidUntil(), l.getMaxMembers(), Set.copyOf(l.getFeatures())));
    }

    private Chama load(UUID chamaId) {
        return chamas.findById(chamaId).orElseThrow(() -> new ChamaNotFoundException(chamaId));
    }
}
