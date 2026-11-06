package ke.chama.modules.auth.api;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only lookup of users for other modules. Always reflects the current database state;
 * prefer it over JWT claims (such as {@code email_verified}) when the answer must be fresh.
 * <p>
 * When auth becomes a separate service, this interface gets an HTTP-client implementation
 * and callers do not change.
 */
public interface UserDirectory {

    Optional<UserSummary> findById(UUID userId);

    Optional<UserSummary> findByEmail(String email);

    /** Unknown IDs are simply absent from the map. */
    Map<UUID, UserSummary> findByIds(Collection<UUID> userIds);
}
