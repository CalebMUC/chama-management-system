package ke.chama.modules.auth.presentation.dto.response;

import ke.chama.modules.auth.application.port.in.AuthTokens;

import java.time.Duration;
import java.time.Instant;

/**
 * Token pair returned by login and refresh. {@code expiresIn} (seconds) follows the OAuth 2
 * convention so clients can schedule a refresh without parsing the JWT.
 */
public record AuthResponse(String accessToken,
                           String tokenType,
                           long expiresIn,
                           Instant accessTokenExpiresAt,
                           String refreshToken,
                           Instant refreshTokenExpiresAt) {

    public static AuthResponse from(AuthTokens tokens, Instant now) {
        // Rounded up: the response is built a few ms after issuing, and 899 for a 15-minute token looks wrong.
        long expiresIn = Math.max(0, Math.ceilDiv(Duration.between(now, tokens.accessTokenExpiresAt()).toMillis(), 1000));
        return new AuthResponse(tokens.accessToken(), "Bearer", expiresIn, tokens.accessTokenExpiresAt(),
                tokens.refreshToken(), tokens.refreshTokenExpiresAt());
    }

    @Override
    public String toString() {
        return "AuthResponse[accessToken=***, refreshToken=***, expiresIn=" + expiresIn + "]";
    }
}
