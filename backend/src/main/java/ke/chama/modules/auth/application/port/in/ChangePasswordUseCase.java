package ke.chama.modules.auth.application.port.in;

import java.util.UUID;

public interface ChangePasswordUseCase {

    /**
     * Changes the password of a logged-in user after checking the current one. Every other
     * session is ended; the returned tokens keep the calling device logged in.
     */
    AuthTokens changePassword(UUID userId, String currentPassword, String newPassword);
}
