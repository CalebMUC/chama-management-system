package ke.chama.modules.auth.application.port.in;

public interface RefreshSessionUseCase {

    /**
     * Exchanges a refresh token for a new token pair. The presented token is single-use:
     * presenting an already-exchanged token again is treated as theft and revokes every
     * session of that user. A token ended by logout is just rejected.
     */
    AuthTokens refresh(String refreshToken);
}
