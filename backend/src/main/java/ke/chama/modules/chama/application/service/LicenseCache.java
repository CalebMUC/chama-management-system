package ke.chama.modules.chama.application.service;

import ke.chama.modules.chama.api.ChamaDirectory;
import ke.chama.modules.chama.api.LicenseSummary;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Licence lookups for the per-request licence check, cached briefly so every request does not
 * hit the database. Licence changes made through this application evict the entry at once;
 * expiry is re-evaluated on every check from {@code validUntil}, so it is never served stale.
 */
@Component
public class LicenseCache {

    static final Duration TTL = Duration.ofSeconds(30);

    private final ChamaDirectory directory;
    private final Clock clock;
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();

    private record Entry(Optional<LicenseSummary> license, Instant loadedAt) {
    }

    LicenseCache(ChamaDirectory directory, Clock clock) {
        this.directory = directory;
        this.clock = clock;
    }

    /** The current tenant's licence. {@code chamaId} must be the current tenant. */
    public Optional<LicenseSummary> get(UUID chamaId) {
        Instant now = clock.instant();
        Entry entry = entries.get(chamaId);
        if (entry == null || entry.loadedAt().plus(TTL).isBefore(now)) {
            entry = new Entry(directory.license(chamaId), now);
            entries.put(chamaId, entry);
        }
        return entry.license();
    }

    /** Whether the licence lets the chama operate at this moment. */
    public boolean usable(LicenseSummary license) {
        return license.usable() && clock.instant().isBefore(license.validUntil());
    }

    void evict(UUID chamaId) {
        entries.remove(chamaId);
    }
}
