package ke.chama.modules.auth.application.port.in;

public interface PasswordResetUseCase {

    /**
     * "Forgot password": emails a reset link if the account exists and is enabled.
     * Returns normally in every case, so callers cannot probe which emails are registered.
     */
    void requestReset(String email);

    /**
     * Sets a new password from a reset link. Ends every session, clears the login lockout
     * and marks the email verified (the link proved the user can read that inbox).
     */
    void reset(String token, String newPassword);
}
