package ke.chama.modules.member.domain.model;

/**
 * <pre>
 * register ──► PENDING_APPROVAL ──approve──► ACTIVE ◄──reactivate── SUSPENDED
 *                     │                         └────────suspend────────┘
 *                     └─reject─► REJECTED
 *
 * ACTIVE or SUSPENDED ──exit settlement paid──► EXITED
 * </pre>
 * Only ACTIVE members hold permissions. A member becomes EXITED when officials pay out their
 * approved exit settlement (the memberexit module); it is final.
 */
public enum MembershipStatus {
    PENDING_APPROVAL,
    REJECTED,
    ACTIVE,
    SUSPENDED,
    EXITED
}
