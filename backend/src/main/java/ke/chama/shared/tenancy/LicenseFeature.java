package ke.chama.shared.tenancy;

/**
 * Functionality a chama's licence can switch on. Controllers of a licensed feature declare it
 * with {@link RequiresFeature}; a chama whose licence lacks it gets 403 {@code LICENSE_FEATURE_NOT_INCLUDED}.
 * <p>
 * Membership, roles, settings and the audit log are always included.
 */
public enum LicenseFeature {
    CONTRIBUTIONS,
    PENALTIES,
    EXPENSES,
    LOANS,
    INVESTMENTS,
    REPORTS,
    MPESA,
    SMS_NOTIFICATIONS
}
