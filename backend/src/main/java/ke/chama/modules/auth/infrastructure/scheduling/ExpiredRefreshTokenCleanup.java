package ke.chama.modules.auth.infrastructure.scheduling;

import ke.chama.modules.auth.application.port.out.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;

/**
 * Deletes refresh-token rows 30 days after they expire. Expired tokens are already unusable;
 * keeping them for a while leaves a trail of who was logged in, when and how each session
 * ended (ROTATED, LOGOUT, REUSE_DETECTED).
 */
@Component
class ExpiredRefreshTokenCleanup {

    private static final Logger log = LoggerFactory.getLogger(ExpiredRefreshTokenCleanup.class);
    static final Duration RETENTION_AFTER_EXPIRY = Duration.ofDays(30);

    private final RefreshTokenRepository refreshTokens;
    private final Clock clock;

    ExpiredRefreshTokenCleanup(RefreshTokenRepository refreshTokens, Clock clock) {
        this.refreshTokens = refreshTokens;
        this.clock = clock;
    }

    @Scheduled(cron = "${chama.auth.token-cleanup-cron:0 30 2 * * *}", zone = "${chama.time-zone:Africa/Nairobi}")
    @Transactional
    void purge() {
        int deleted = refreshTokens.deleteExpiredBefore(clock.instant().minus(RETENTION_AFTER_EXPIRY));
        if (deleted > 0) {
            log.info("Deleted {} refresh token(s) expired more than {} days ago", deleted, RETENTION_AFTER_EXPIRY.toDays());
        }
    }
}
