package ke.chama.modules.auth.application.port.in;

import java.time.Instant;

/** A fresh session: short-lived access token plus a single-use refresh token. */
public record AuthTokens(String accessToken,
                         Instant accessTokenExpiresAt,
                         String refreshToken,
                         Instant refreshTokenExpiresAt) {
}
