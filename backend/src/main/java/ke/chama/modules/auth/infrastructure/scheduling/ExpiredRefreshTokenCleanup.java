package ke.chama.modules.auth.infrastructure.scheduling;

import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import ke.chama.shared.tenancy.TenantContext;
import ke.chama.modules.auth.application.port.out.LoginAttemptRepository;
import ke.chama.modules.auth.application.port.out.OneTimeTokenRepository;
import ke.chama.modules.auth.application.port.out.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Nightly retention for auth security records. Expired tokens are already unusable;
 * keeping them for a while leaves a trail of who was logged in, when, and how each session
 * or link ended.
 */
@Component
class ExpiredRefreshTokenCleanup {

    private static final Logger log = LoggerFactory.getLogger(ExpiredRefreshTokenCleanup.class);

    static final Duration TOKEN_RETENTION_AFTER_EXPIRY = Duration.ofDays(30);
    static final Duration LOGIN_ATTEMPT_RETENTION = Duration.ofDays(90);

    private final RefreshTokenRepository refreshTokens;
    private final OneTimeTokenRepository oneTimeTokens;
    private final LoginAttemptRepository loginAttempts;
    private final Clock clock;
    private final TransactionTemplate transaction;

    ExpiredRefreshTokenCleanup(RefreshTokenRepository refreshTokens,
                               OneTimeTokenRepository oneTimeTokens,
                               LoginAttemptRepository loginAttempts,
                               Clock clock,
                               PlatformTransactionManager transactionManager) {
        this.refreshTokens = refreshTokens;
        this.oneTimeTokens = oneTimeTokens;
        this.loginAttempts = loginAttempts;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Scheduled(cron = "${chama.auth.token-cleanup-cron:0 30 2 * * *}", zone = "${chama.time-zone:Africa/Nairobi}")
    void purge() {
        // Housekeeping spans every chama, so it runs in system mode (no tenant filter).
        TenantContext.runAsSystem(() -> transaction.executeWithoutResult(status -> purgeAllTenants()));
    }

    private void purgeAllTenants() {
        Instant now = clock.instant();
        int sessions = refreshTokens.deleteExpiredBefore(now.minus(TOKEN_RETENTION_AFTER_EXPIRY));
        int links = oneTimeTokens.deleteExpiredBefore(now.minus(TOKEN_RETENTION_AFTER_EXPIRY));
        int attempts = loginAttempts.deleteOlderThan(now.minus(LOGIN_ATTEMPT_RETENTION));
        if (sessions + links + attempts > 0) {
            log.info("Auth retention: deleted {} refresh token(s), {} email link(s), {} login attempt(s)",
                    sessions, links, attempts);
        }
    }
}
