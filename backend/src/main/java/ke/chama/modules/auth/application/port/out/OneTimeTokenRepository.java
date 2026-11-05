package ke.chama.modules.auth.application.port.out;

import ke.chama.modules.auth.domain.model.OneTimeToken;
import ke.chama.modules.auth.domain.model.TokenPurpose;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface OneTimeTokenRepository {

    OneTimeToken save(OneTimeToken token);

    Optional<OneTimeToken> findByTokenHash(String tokenHash);

    /** Atomically marks the token used if it is still usable. Returns 0 if another request got there first. */
    int markUsedIfUsable(UUID tokenId, Instant now);

    /** Invalidates the user's unused tokens for this purpose, so only the newest link works. */
    int invalidateActive(UUID userId, TokenPurpose purpose, Instant now);

    Optional<Instant> latestCreatedAt(UUID userId, TokenPurpose purpose);

    int deleteExpiredBefore(Instant cutoff);
}
