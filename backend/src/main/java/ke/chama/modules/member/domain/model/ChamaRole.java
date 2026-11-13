package ke.chama.modules.member.domain.model;

/**
 * Roles a member can hold in one chama; a member can hold several. Every member always holds
 * MEMBER. What each role may do is configured per chama (see {@link DefaultRolePermissions}).
 */
public enum ChamaRole {
    MEMBER,
    TREASURER,
    SECRETARY,
    CHAIRPERSON,
    /** Independent internal auditor: can see everything, change nothing. */
    AUDITOR,
    /**
     * Runs the chama's operations: invites, approves and suspends members, assigns their roles,
     * and keeps settings and products up to date. Handles no money.
     */
    ADMIN
}
