package ke.chama.modules.auth.domain.exception;

import ke.chama.shared.domain.exception.ErrorType;
import ke.chama.shared.domain.exception.RetryAfterAware;

import java.time.Duration;

/**
 * Too many failed logins for this email. Raised for unknown emails too, so it does not
 * reveal whether an account exists.
 */
public class AccountLockedException extends AuthException implements RetryAfterAware {

    private final Duration retryAfter;

    public AccountLockedException(Duration retryAfter) {
        super("AUTH_ACCOUNT_LOCKED", ErrorType.TOO_MANY_REQUESTS,
                "Too many failed login attempts. Try again in " + minutes(retryAfter)
                        + " minute(s), or reset your password.");
        this.retryAfter = retryAfter;
    }

    @Override
    public Duration retryAfter() {
        return retryAfter;
    }

    private static long minutes(Duration duration) {
        return Math.max(1, Math.ceilDiv(duration.toSeconds(), 60));
    }
}
