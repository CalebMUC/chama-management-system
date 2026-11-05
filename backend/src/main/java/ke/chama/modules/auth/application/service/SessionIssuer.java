package ke.chama.modules.auth.application.service;

import ke.chama.modules.auth.application.AuthProperties;
import ke.chama.modules.auth.application.port.in.AuthTokens;
import ke.chama.modules.auth.application.port.out.AccessTokenIssuer;
import ke.chama.modules.auth.application.port.out.RefreshTokenRepository;
import ke.chama.modules.auth.domain.model.RefreshToken;
import ke.chama.modules.auth.domain.model.RevocationReason;
import ke.chama.modules.auth.domain.model.User;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/** Starts and ends sessions: an access JWT plus a stored (hashed) refresh token. */
@Component
class SessionIssuer {

    private final AccessTokenIssuer accessTokenIssuer;
    private final RefreshTokenRepository refreshTokens;
    private final AuthProperties properties;

    SessionIssuer(AccessTokenIssuer accessTokenIssuer, RefreshTokenRepository refreshTokens, AuthProperties properties) {
        this.accessTokenIssuer = accessTokenIssuer;
        this.refreshTokens = refreshTokens;
        this.properties = properties;
    }

    AuthTokens start(User user, Instant now) {
        AccessTokenIssuer.IssuedAccessToken accessToken = accessTokenIssuer.issue(user, now);

        String rawRefreshToken = OpaqueTokens.generate();
        Instant refreshExpiresAt = now.plus(properties.refreshTokenTtl());
        refreshTokens.save(new RefreshToken(user, OpaqueTokens.sha256Hex(rawRefreshToken), refreshExpiresAt));

        return new AuthTokens(accessToken.value(), accessToken.expiresAt(), rawRefreshToken, refreshExpiresAt);
    }

    int endAll(UUID userId, RevocationReason reason, Instant now) {
        return refreshTokens.revokeAllActiveForUser(userId, now, reason);
    }
}
