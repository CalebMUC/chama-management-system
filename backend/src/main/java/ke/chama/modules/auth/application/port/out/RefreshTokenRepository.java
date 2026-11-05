package ke.chama.modules.auth.application.port.out;

import ke.chama.modules.auth.domain.model.RefreshToken;
import ke.chama.modules.auth.domain.model.RevocationReason;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository {

    RefreshToken save(RefreshToken token);

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Atomically revokes the token only if it is still active. Returns 0 when another request
     * already used it, which is how concurrent reuse is detected without locks.
     */
    int revokeIfActive(UUID tokenId, Instant revokedAt, RevocationReason reason);

    int revokeAllActiveForUser(UUID userId, Instant revokedAt, RevocationReason reason);

    int deleteExpiredBefore(Instant cutoff);
}
