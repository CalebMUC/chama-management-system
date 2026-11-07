package ke.chama.modules.member.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only member lookup for other modules, within the current chama. "Seated" members are
 * ACTIVE or SUSPENDED: a suspended member keeps their seat and their obligations.
 */
public interface MemberDirectory {

    /** Every seated member, by member number. */
    List<MemberSummary> seatedMembers(UUID chamaId);

    /** The member, if seated. */
    Optional<MemberSummary> seatedMember(UUID chamaId, UUID userId);

    /** How many memberships are in each state that matters day to day. */
    MembershipCounts counts(UUID chamaId);

    /** @param pendingApproval applications waiting for an official to approve or reject them */
    record MembershipCounts(long active, long suspended, long pendingApproval) {
    }
}
