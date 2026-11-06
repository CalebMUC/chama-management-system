package ke.chama.modules.auth.application.port.out;

import ke.chama.modules.auth.domain.model.LoginAttempt;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface LoginAttemptRepository {

    LoginAttempt save(LoginAttempt attempt);

    /** Times of BAD_CREDENTIALS attempts for the email after {@code since}, newest first, at most {@code limit}. */
    List<Instant> recentBadCredentialTimes(String email, Instant since, int limit);

    Optional<Instant> lastSuccessAt(String email);

    int deleteOlderThan(Instant cutoff);
}
