package ke.chama.modules.chama.application.service;

import ke.chama.modules.audit.api.AuditEntry;
import ke.chama.modules.audit.api.AuditTrail;
import ke.chama.modules.auth.api.UserDirectory;
import ke.chama.modules.auth.api.UserSummary;
import ke.chama.modules.chama.api.ChamaDirectory;
import ke.chama.modules.chama.api.ChamaSummary;
import ke.chama.modules.chama.application.port.in.ChamaUseCases;
import ke.chama.modules.chama.application.port.in.ChamaView;
import ke.chama.modules.chama.application.port.out.ChamaRepository;
import ke.chama.modules.chama.domain.exception.ChamaNotFoundException;
import ke.chama.modules.chama.domain.exception.FounderEmailNotVerifiedException;
import ke.chama.modules.chama.domain.model.Chama;
import ke.chama.modules.chama.domain.model.ChamaSettings;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Currency;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional
public class ChamaService implements ChamaUseCases, ChamaDirectory {

    static final String TARGET = "CHAMA";

    private final ChamaRepository chamas;
    private final UserDirectory userDirectory;
    private final AuditTrail auditTrail;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ChamaService(ChamaRepository chamas, UserDirectory userDirectory, AuditTrail auditTrail,
                        ApplicationEventPublisher events, Clock clock) {
        this.chamas = chamas;
        this.userDirectory = userDirectory;
        this.auditTrail = auditTrail;
        this.events = events;
        this.clock = clock;
    }

    @Override
    public ChamaView found(UUID founderUserId, String name, String description, Currency currency, ChamaSettings settings) {
        Instant now = clock.instant();
        UserSummary founder = userDirectory.findById(founderUserId)
                .filter(UserSummary::enabled)
                .orElseThrow(FounderEmailNotVerifiedException::new);
        if (!founder.emailVerified()) {
            throw new FounderEmailNotVerifiedException();
        }

        Chama chama = Chama.found(name, description, currency, settings, founder.id(), founder.email(), now);
        chamas.save(chama);

        auditTrail.record(AuditEntry.of(chama.getId(), founder.id(), "CHAMA_CREATED", TARGET, chama.getId())
                .with(Map.of("name", chama.getName(), "currency", chama.getCurrency().getCurrencyCode(),
                        "settings", settings.asMap())));
        // ChamaCreated is handled synchronously by the member module (founder becomes chairperson).
        chama.pullDomainEvents().forEach(events::publishEvent);
        return ChamaView.from(chama);
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
    public Optional<ChamaSummary> find(UUID chamaId) {
        return chamas.findById(chamaId).map(ChamaService::toSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, ChamaSummary> findAll(Collection<UUID> chamaIds) {
        if (chamaIds.isEmpty()) {
            return Map.of();
        }
        return chamas.findAllById(chamaIds).stream()
                .map(ChamaService::toSummary)
                .collect(Collectors.toMap(ChamaSummary::id, Function.identity()));
    }

    private Chama load(UUID chamaId) {
        return chamas.findById(chamaId).orElseThrow(() -> new ChamaNotFoundException(chamaId));
    }

    private static ChamaSummary toSummary(Chama chama) {
        return new ChamaSummary(chama.getId(), chama.getName(), chama.getCurrency().getCurrencyCode(), chama.isActive());
    }
}
