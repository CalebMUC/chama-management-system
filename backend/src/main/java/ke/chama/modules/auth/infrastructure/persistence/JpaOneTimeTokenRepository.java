package ke.chama.modules.auth.infrastructure.persistence;

import ke.chama.modules.auth.application.port.out.OneTimeTokenRepository;
import ke.chama.modules.auth.domain.model.OneTimeToken;
import ke.chama.modules.auth.domain.model.TokenPurpose;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

interface JpaOneTimeTokenRepository extends JpaRepository<OneTimeToken, UUID>, OneTimeTokenRepository {

    @Override
    @EntityGraph(attributePaths = "user")
    Optional<OneTimeToken> findByTokenHash(String tokenHash);

    /** Conditional UPDATE: two clicks on the same link cannot both succeed. */
    @Override
    @Modifying(flushAutomatically = true)
    @Query("""
            update OneTimeToken t set t.usedAt = :now
             where t.id = :tokenId and t.usedAt is null and t.invalidatedAt is null and t.expiresAt > :now""")
    int markUsedIfUsable(@Param("tokenId") UUID tokenId, @Param("now") Instant now);

    @Override
    @Modifying(flushAutomatically = true)
    @Query("""
            update OneTimeToken t set t.invalidatedAt = :now
             where t.user.id = :userId and t.purpose = :purpose and t.usedAt is null and t.invalidatedAt is null""")
    int invalidateActive(@Param("userId") UUID userId, @Param("purpose") TokenPurpose purpose, @Param("now") Instant now);

    @Override
    @Query("select max(t.createdAt) from OneTimeToken t where t.user.id = :userId and t.purpose = :purpose")
    Optional<Instant> latestCreatedAt(@Param("userId") UUID userId, @Param("purpose") TokenPurpose purpose);

    @Override
    @Modifying
    @Query("delete from OneTimeToken t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
