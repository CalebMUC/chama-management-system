package ke.chama.modules.auth.infrastructure.security;

import ke.chama.shared.tenancy.TenantContextFilter;
import ke.chama.shared.tenancy.TenantContext;
import ke.chama.modules.auth.application.AuthProperties;
import ke.chama.modules.auth.application.port.out.AccessTokenIssuer;
import ke.chama.modules.auth.domain.model.Role;
import ke.chama.modules.auth.domain.model.User;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Access token claims: {@code sub} (user id), {@code email}, {@code email_verified}, {@code roles} (platform roles),
 * {@code iss}, {@code iat}, {@code exp}, {@code jti}.
 * <p>
 * Chama permissions are deliberately left out. They are looked up per request (Phase 2), so
 * removing a treasurer takes effect immediately instead of when the token expires.
 */
@Component
class JwtAccessTokenIssuer implements AccessTokenIssuer {

    static final String EMAIL_CLAIM = "email";
    /** Standard OIDC claim name. */
    static final String EMAIL_VERIFIED_CLAIM = "email_verified";
    static final String ROLES_CLAIM = "roles";

    private final JwtEncoder encoder;
    private final AuthProperties properties;

    JwtAccessTokenIssuer(JwtEncoder encoder, AuthProperties properties) {
        this.encoder = encoder;
        this.properties = properties;
    }

    @Override
    public IssuedAccessToken issue(User user, Instant issuedAt) {
        Instant expiresAt = issuedAt.plus(properties.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(user.getId().toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim(EMAIL_CLAIM, user.getEmail())
                .claim(EMAIL_VERIFIED_CLAIM, user.isEmailVerified())
                .claim(ROLES_CLAIM, user.getRoles().stream().map(Role::getName).sorted().toList())
                // The tenant always comes from the user record, never from the request.
                .claim(TenantContextFilter.TENANT_ID_CLAIM, user.getTenantId().toString())
                .claims(c -> TenantContext.code()
                        .filter(code -> TenantContext.id().map(user.getTenantId()::equals).orElse(false))
                        .ifPresent(code -> c.put(TenantContextFilter.TENANT_CODE_CLAIM, code)))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedAccessToken(token, expiresAt);
    }
}
