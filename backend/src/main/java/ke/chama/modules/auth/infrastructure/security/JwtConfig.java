package ke.chama.modules.auth.infrastructure.security;

import ke.chama.modules.auth.application.AuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * HS256 signing and verification with one shared secret.
 * <p>
 * When modules become services, every service gets a JwtDecoder. At that point, switch to
 * RS256 (private key here, public JWKS for the others) so services can verify tokens but
 * not mint them.
 */
@Configuration(proxyBeanMethods = false)
class JwtConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtConfig.class);

    private final SecretKey signingKey;

    JwtConfig(AuthProperties properties, Environment environment) {
        if (properties.usesDevelopmentSecret()) {
            if (environment.matchesProfiles("prod")) {
                throw new IllegalStateException(
                        "Refusing to start: the local development JWT secret is in use with the 'prod' profile. Set JWT_SECRET.");
            }
            log.warn("Using the local development JWT secret. Set JWT_SECRET for any shared or deployed environment.");
        }
        this.signingKey = new SecretKeySpec(properties.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder() {
        return NimbusJwtEncoder.withSecretKey(signingKey).algorithm(MacAlgorithm.HS256).build();
    }

    /** Checks signature, expiry (60s clock skew) and issuer. */
    @Bean
    JwtDecoder jwtDecoder(AuthProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(signingKey).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }
}
