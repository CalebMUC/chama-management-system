package ke.chama.modules.auth.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * Auth settings bound from {@code chama.auth.*}. Validated at startup, so a misconfigured
 * deployment refuses to boot instead of issuing weak tokens.
 *
 * @param requireVerifiedEmail when true, login is refused until the email is verified
 */
@ConfigurationProperties(prefix = "chama.auth")
public record AuthProperties(String issuer,
                             String jwtSecret,
                             Duration accessTokenTtl,
                             Duration refreshTokenTtl,
                             @DefaultValue("false") boolean requireVerifiedEmail,
                             @DefaultValue Lockout lockout,
                             @DefaultValue Links links) {

    static final String DEV_SECRET_PREFIX = "local-dev-only";

    public AuthProperties {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException("chama.auth.issuer must be set");
        }
        if (jwtSecret == null || jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "chama.auth.jwt-secret (env JWT_SECRET) must be at least 256 bits (32 bytes) for HS256");
        }
        requirePositive(accessTokenTtl, "access-token-ttl");
        requirePositive(refreshTokenTtl, "refresh-token-ttl");
        if (refreshTokenTtl.compareTo(accessTokenTtl) <= 0) {
            throw new IllegalStateException("chama.auth.refresh-token-ttl must exceed access-token-ttl");
        }
        Objects.requireNonNull(lockout, "chama.auth.lockout");
        Objects.requireNonNull(links, "chama.auth.links");
    }

    /** True when running on the committed local-development secret. */
    public boolean usesDevelopmentSecret() {
        return jwtSecret.startsWith(DEV_SECRET_PREFIX);
    }

    /**
     * After {@code maxFailedAttempts} wrong passwords within {@code duration}, login for that
     * email is refused until the oldest of those failures is {@code duration} old.
     */
    public record Lockout(@DefaultValue("5") int maxFailedAttempts,
                          @DefaultValue("15m") Duration duration) {
        public Lockout {
            if (maxFailedAttempts < 1) {
                throw new IllegalStateException("chama.auth.lockout.max-failed-attempts must be at least 1");
            }
            requirePositive(duration, "lockout.duration");
        }
    }

    /**
     * Links in emails point at the frontend, which posts the token back to the API.
     * They are never plain GET links to the API: mail scanners open those automatically.
     */
    public record Links(@DefaultValue("http://localhost:3000") String frontendBaseUrl,
                        @DefaultValue("24h") Duration emailVerificationTtl,
                        @DefaultValue("30m") Duration passwordResetTtl,
                        @DefaultValue("60s") Duration resendInterval,
                        @DefaultValue("7d") Duration accountSetupTtl) {
        public Links {
            if (frontendBaseUrl == null || frontendBaseUrl.isBlank()) {
                throw new IllegalStateException("chama.auth.links.frontend-base-url must be set");
            }
            frontendBaseUrl = frontendBaseUrl.endsWith("/")
                    ? frontendBaseUrl.substring(0, frontendBaseUrl.length() - 1)
                    : frontendBaseUrl;
            requirePositive(emailVerificationTtl, "links.email-verification-ttl");
            requirePositive(passwordResetTtl, "links.password-reset-ttl");
            Objects.requireNonNull(resendInterval, "links.resend-interval");
            requirePositive(accountSetupTtl, "links.account-setup-ttl");
        }
    }

    private static void requirePositive(Duration duration, String name) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            throw new IllegalStateException("chama.auth." + name + " must be a positive duration");
        }
    }
}
