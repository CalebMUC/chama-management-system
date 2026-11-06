package ke.chama.modules.auth.domain.model;

public enum LoginOutcome {
    SUCCESS,
    /** Unknown email or wrong password. The only outcome that counts toward lockout. */
    BAD_CREDENTIALS,
    /** Rejected without checking the password because of too many recent failures. */
    ACCOUNT_LOCKED,
    ACCOUNT_DISABLED,
    EMAIL_NOT_VERIFIED
}
