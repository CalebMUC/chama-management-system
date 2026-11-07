package ke.chama.modules.member.api;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * Seats members brought over from a chama's earlier records (spreadsheets). Runs in the caller's
 * transaction; the caller has created the person's account and checked the licence's member limit.
 */
public interface MemberImport {

    /** Role names a member can hold, e.g. MEMBER, TREASURER. */
    Set<String> roleNames();

    /**
     * Seats the member: active from {@code joinedOn}, numbered after the existing members, with
     * {@code roles} (MEMBER is always included). Audit-logged as MEMBER_IMPORTED.
     */
    MemberSummary importMember(UUID chamaId, UUID userId, String email, LocalDate joinedOn, Set<String> roles,
                               UUID importedBy);
}
