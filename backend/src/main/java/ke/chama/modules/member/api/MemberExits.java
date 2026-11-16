package ke.chama.modules.member.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Ending a membership, for the module that settles a member's exit. The settlement (savings,
 * loans, penalties) is that module's business; this one only knows whether the membership can
 * end and records that it did.
 */
public interface MemberExits {

    /**
     * Why the membership cannot end now, e.g. the member is the chama's only active chairperson.
     * Empty when it can.
     */
    List<String> exitBlockers(UUID chamaId, UUID userId);

    /**
     * Ends a seated member's membership (EXITED). Runs in the caller's transaction, which must
     * exist; audit-logged as MEMBER_EXITED.
     */
    void recordExit(UUID chamaId, UUID userId, UUID actorUserId, String reason, Instant exitedAt);
}
