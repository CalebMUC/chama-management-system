package ke.chama.modules.auth.application.port.in;

public interface EmailVerificationUseCase {

    /**
     * Sends a fresh verification link if the account exists, is enabled and is not yet verified.
     * Returns normally in every case, so callers cannot probe which emails are registered.
     * At most one email per resend interval.
     */
    void requestVerification(String email);

    /** Marks the email verified. Throws InvalidLinkException for unknown, used or expired tokens. */
    void verify(String token);
}
