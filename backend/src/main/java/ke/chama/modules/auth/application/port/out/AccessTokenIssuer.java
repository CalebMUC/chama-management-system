package ke.chama.modules.auth.application.port.out;

import ke.chama.modules.auth.domain.model.User;

import java.time.Instant;

public interface AccessTokenIssuer {

    IssuedAccessToken issue(User user, Instant issuedAt);

    record IssuedAccessToken(String value, Instant expiresAt) {
    }
}
