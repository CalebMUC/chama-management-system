package ke.chama.modules.chama.domain.model;

/**
 * Whether a licence lets the chama operate right now. Derived from the stored status and the
 * validity period: a licence expires on its own, without anyone changing it.
 */
public enum LicenseState {
    /** Valid: the chama works normally. */
    ACTIVE,
    /** Past {@code validUntil}: renew to continue. */
    EXPIRED,
    /** Switched off by a platform administrator, e.g. for non-payment. */
    SUSPENDED
}
