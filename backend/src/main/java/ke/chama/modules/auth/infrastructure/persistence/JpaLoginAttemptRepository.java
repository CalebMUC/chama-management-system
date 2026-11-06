package ke.chama.modules.auth.infrastructure.persistence;

import ke.chama.modules.auth.application.port.out.LoginAttemptRepository;
import ke.chama.modules.auth.domain.model.LoginAttempt;
import ke.chama.modules.auth.domain.model.LoginOutcome;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface JpaLoginAttemptRepository extends JpaRepository<LoginAttempt, UUID>, LoginAttemptRepository {

    @Query("""
            select a.attemptedAt from LoginAttempt a
             where a.email = :email and a.outcome = :outcome and a.attemptedAt > :since
             order by a.attemptedAt desc""")
    List<Instant> findAttemptTimes(@Param("email") String email,
                                   @Param("outcome") LoginOutcome outcome,
                                   @Param("since") Instant since,
                                   Limit limit);

    @Query("select max(a.attemptedAt) from LoginAttempt a where a.email = :email and a.outcome = :outcome")
    Optional<Instant> findLatestAttemptTime(@Param("email") String email, @Param("outcome") LoginOutcome outcome);

    @Override
    default List<Instant> recentBadCredentialTimes(String email, Instant since, int limit) {
        return findAttemptTimes(email, LoginOutcome.BAD_CREDENTIALS, since, Limit.of(limit));
    }

    @Override
    default Optional<Instant> lastSuccessAt(String email) {
        return findLatestAttemptTime(email, LoginOutcome.SUCCESS);
    }

    @Override
    @Modifying
    @Query("delete from LoginAttempt a where a.attemptedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
