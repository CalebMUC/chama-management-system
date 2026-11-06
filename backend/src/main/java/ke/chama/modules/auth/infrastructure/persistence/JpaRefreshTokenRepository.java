package ke.chama.modules.auth.infrastructure.persistence;

import ke.chama.modules.auth.application.port.out.RefreshTokenRepository;
import ke.chama.modules.auth.domain.model.RefreshToken;
import ke.chama.modules.auth.domain.model.RevocationReason;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

interface JpaRefreshTokenRepository extends JpaRepository<RefreshToken, UUID>, RefreshTokenRepository {

    @Override
    @EntityGraph(attributePaths = "user")
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Conditional UPDATE: a single statement, so two concurrent refreshes cannot both win. */
    @Override
    @Modifying(flushAutomatically = true)
    @Query("""
            update RefreshToken t
               set t.revoked = true, t.revokedAt = :revokedAt, t.revokedReason = :reason
             where t.id = :tokenId and t.revoked = false""")
    int revokeIfActive(@Param("tokenId") UUID tokenId,
                       @Param("revokedAt") Instant revokedAt,
                       @Param("reason") RevocationReason reason);

    @Override
    @Modifying(flushAutomatically = true)
    @Query("""
            update RefreshToken t
               set t.revoked = true, t.revokedAt = :revokedAt, t.revokedReason = :reason
             where t.user.id = :userId and t.revoked = false""")
    int revokeAllActiveForUser(@Param("userId") UUID userId,
                               @Param("revokedAt") Instant revokedAt,
                               @Param("reason") RevocationReason reason);

    @Override
    @Modifying
    @Query("delete from RefreshToken t where t.expiryDate < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
