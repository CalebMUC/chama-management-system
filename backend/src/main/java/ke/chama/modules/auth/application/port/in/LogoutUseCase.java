package ke.chama.modules.auth.application.port.in;

import java.util.UUID;

public interface LogoutUseCase {

    /** Ends one session. Idempotent: unknown or already-revoked tokens are ignored. */
    void logout(String refreshToken);

    /** Ends every session of the user, e.g. "log out of all devices" after a lost phone. */
    void logoutAllSessions(UUID userId);
}
