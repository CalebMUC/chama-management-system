package ke.chama.modules.member.domain.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * <pre>
 * INVITED ──accept──► PENDING_APPROVAL ──approve──► ACTIVE ◄──reactivate── SUSPENDED
 *    │                      │                         └────────suspend────────┘
 *    └─decline─► DECLINED   └─reject─► REJECTED
 * </pre>
 * Only ACTIVE members hold permissions. EXITED is reserved for leaving the chama, which needs
 * final settlement of savings and loans (a later phase).
 */
public enum MembershipStatus {
    INVITED,
    DECLINED,
    PENDING_APPROVAL,
    REJECTED,
    ACTIVE,
    SUSPENDED,
    EXITED;

    /** Statuses that block a second membership for the same person in the same chama. */
    public static final Set<MembershipStatus> OPEN = EnumSet.of(INVITED, PENDING_APPROVAL, ACTIVE, SUSPENDED);
}
