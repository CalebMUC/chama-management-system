package ke.chama.modules.auth.application.service;

import ke.chama.modules.auth.application.AuthProperties;
import ke.chama.modules.auth.application.port.out.LoginAttemptRepository;
import ke.chama.modules.auth.domain.model.LoginAttempt;
import ke.chama.modules.auth.domain.model.LoginOutcome;
import ke.chama.modules.auth.domain.model.User;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Brute-force protection keyed by email.
 * <p>
 * Locked when the last {@code maxFailedAttempts} BAD_CREDENTIALS attempts all fall within
 * {@code duration} and after the latest "reset point". Reset points are the last successful
 * login and the last password change, so a correct login or a password reset clears the lock.
 * <p>
 * Requests refused while locked are recorded as ACCOUNT_LOCKED but do not extend the lock;
 * otherwise an attacker could keep a member locked out indefinitely.
 */
@Component
class LoginThrottle {

    private final LoginAttemptRepository attempts;
    private final AuthProperties.Lockout policy;

    LoginThrottle(LoginAttemptRepository attempts, AuthProperties properties) {
        this.attempts = attempts;
        this.policy = properties.lockout();
    }

    /** When the lock lifts, if the email is currently locked. */
    Optional<Instant> lockedUntil(String email, User user, Instant now) {
        Instant since = Stream.of(
                        now.minus(policy.duration()),
                        attempts.lastSuccessAt(email).orElse(null),
                        user != null ? user.getPasswordChangedAt() : null)
                .filter(java.util.Objects::nonNull)
                .max(Instant::compareTo)
                .orElseThrow();

        List<Instant> failures = attempts.recentBadCredentialTimes(email, since, policy.maxFailedAttempts());
        if (failures.size() < policy.maxFailedAttempts()) {
            return Optional.empty();
        }
        // The lock lifts when the oldest of the counted failures leaves the window.
        return Optional.of(failures.getLast().plus(policy.duration()));
    }

    void record(String email, User user, LoginOutcome outcome, String ipAddress, String userAgent, Instant now) {
        UUID userId = user != null ? user.getId() : null;
        attempts.save(new LoginAttempt(email, userId, outcome, ipAddress, userAgent, now));
    }
}
