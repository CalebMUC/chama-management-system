package ke.chama.modules.auth.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * Auth settings bound from {@code chama.auth.*}. Validated at startup, so a misconfigured
 * deployment refuses to boot instead of issuing weak tokens.
 */
@ConfigurationProperties(prefix = "chama.auth")
public record AuthProperties(String issuer,
                             String jwtSecret,
                             Duration accessTokenTtl,
                             Duration refreshTokenTtl) {

    static final String DEV_SECRET_PREFIX = "local-dev-only";

    public AuthProperties {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException("chama.auth.issuer must be set");
        }
        if (jwtSecret == null || jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "chama.auth.jwt-secret (env JWT_SECRET) must be at least 256 bits (32 bytes) for HS256");
        }
        Objects.requireNonNull(accessTokenTtl, "chama.auth.access-token-ttl must be set");
        Objects.requireNonNull(refreshTokenTtl, "chama.auth.refresh-token-ttl must be set");
        if (accessTokenTtl.isNegative() || accessTokenTtl.isZero()
                || refreshTokenTtl.compareTo(accessTokenTtl) <= 0) {
            throw new IllegalStateException("Token TTLs must be positive and refresh TTL must exceed access TTL");
        }
    }

    /** True when running on the committed local-development secret. */
    public boolean usesDevelopmentSecret() {
        return jwtSecret.startsWith(DEV_SECRET_PREFIX);
    }
}
