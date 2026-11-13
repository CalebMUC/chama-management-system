package ke.chama.modules.member.domain.model;

/** The identity document a member's KYC record is based on. */
public enum IdDocumentType {
    NATIONAL_ID,
    PASSPORT,
    /** Kenyan alien (foreign resident) card. */
    ALIEN_ID
}
