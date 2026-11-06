package ke.chama.shared.domain.exception;

/**
 * Category of a domain failure. The domain says what went wrong; the web layer
 * decides which HTTP status that becomes (see GlobalExceptionHandler).
 */
public enum ErrorType {
    /** Input is malformed or out of range. HTTP 400. */
    VALIDATION,
    /** Caller is not authenticated or credentials/token are invalid. HTTP 401. */
    UNAUTHORIZED,
    /** Caller is authenticated but not allowed, e.g. approving their own loan. HTTP 403. */
    FORBIDDEN,
    /** The referenced resource does not exist (in this chama). HTTP 404. */
    NOT_FOUND,
    /** Clashes with existing state, e.g. a duplicate M-Pesa reference. HTTP 409. */
    CONFLICT,
    /** Valid request that breaks a business rule, e.g. loan exceeds 3x savings. HTTP 422. */
    BUSINESS_RULE,
    /** Throttled, e.g. too many failed logins. HTTP 429, with Retry-After when known. */
    TOO_MANY_REQUESTS
}
