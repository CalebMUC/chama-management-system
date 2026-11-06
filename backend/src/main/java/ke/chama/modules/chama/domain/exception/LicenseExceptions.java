package ke.chama.modules.chama.domain.exception;

import ke.chama.shared.domain.exception.DomainException;
import ke.chama.shared.domain.exception.ErrorType;

/** Licensing and onboarding errors. Codes are prefixed LICENSE_ or CHAMA_. */
public final class LicenseExceptions {

    private LicenseExceptions() {
    }

    /** The chama's licence has expired: everything except viewing the chama and its licence is refused. */
    public static class LicenseExpiredException extends DomainException {
        public LicenseExpiredException() {
            super("LICENSE_EXPIRED", ErrorType.FORBIDDEN,
                    "Your chama's licence has expired. Ask your chairperson to renew it to continue.");
        }
    }

    public static class LicenseSuspendedException extends DomainException {
        public LicenseSuspendedException() {
            super("LICENSE_SUSPENDED", ErrorType.FORBIDDEN,
                    "Your chama's licence is suspended. Contact the platform provider.");
        }
    }

    public static class FeatureNotLicensedException extends DomainException {
        public FeatureNotLicensedException(String feature) {
            super("LICENSE_FEATURE_NOT_INCLUDED", ErrorType.FORBIDDEN,
                    "Your chama's licence does not include " + feature + ". Upgrade the licence to use it.");
        }
    }

    public static class LicenseNotFoundException extends DomainException {
        public LicenseNotFoundException() {
            super("LICENSE_NOT_FOUND", ErrorType.NOT_FOUND, "This chama has no licence");
        }
    }

    public static class ChamaCodeTakenException extends DomainException {
        public ChamaCodeTakenException(String code) {
            super("CHAMA_CODE_TAKEN", ErrorType.CONFLICT, "The chama code '" + code + "' is already taken. Choose another.");
        }
    }
}
