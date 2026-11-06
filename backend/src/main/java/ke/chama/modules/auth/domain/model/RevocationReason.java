package ke.chama.modules.auth.domain.model;

/** Why a refresh token stopped working. Kept for security investigations. */
public enum RevocationReason {
    /**
     * Exchanged for a new token pair during a normal refresh. If a ROTATED token is presented
     * again, a copy of it exists somewhere, so it triggers {@link #REUSE_DETECTED}.
     */
    ROTATED,
    /** The user logged out of this session. */
    LOGOUT,
    /** The user logged out of every session. */
    LOGOUT_ALL,
    /** The password was reset or changed; every existing session must log in again. */
    PASSWORD_CHANGED,
    /**
     * An already-exchanged token was presented again, or two requests raced to use one token.
     * Either it was stolen or a client misbehaved; in both cases every session for the user
     * is revoked.
     */
    REUSE_DETECTED
}
