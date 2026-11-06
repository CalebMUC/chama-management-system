package ke.chama.modules.member.domain.model;

/**
 * <pre>
 * register ──► PENDING_APPROVAL ──approve──► ACTIVE ◄──reactivate── SUSPENDED
 *                     │                         └────────suspend────────┘
 *                     └─reject─► REJECTED
 * </pre>
 * Only ACTIVE members hold permissions. EXITED is reserved for leaving the chama, which needs
 * final settlement of savings and loans (a later phase).
 */
public enum MembershipStatus {
    PENDING_APPROVAL,
    REJECTED,
    ACTIVE,
    SUSPENDED,
    EXITED
}
