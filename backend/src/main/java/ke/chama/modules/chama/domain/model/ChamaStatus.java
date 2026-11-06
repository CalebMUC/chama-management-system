package ke.chama.modules.chama.domain.model;

public enum ChamaStatus {
    ACTIVE,
    /** Read-only: history stays available but no new transactions are accepted. */
    ARCHIVED
}
