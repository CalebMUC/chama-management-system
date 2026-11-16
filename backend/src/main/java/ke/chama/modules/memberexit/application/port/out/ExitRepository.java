package ke.chama.modules.memberexit.application.port.out;

import ke.chama.modules.memberexit.domain.model.MemberExit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.UUID;

/** Row-level security limits every query to the current chama. Exits load with their lines and approvals. */
public interface ExitRepository {

    void insert(MemberExit exit);

    /** Saves the exit and its approvals; false if it changed since it was read. */
    boolean update(MemberExit exit);

    Optional<MemberExit> exit(UUID chamaId, UUID exitId);

    /** Newest first; {@code status} and {@code memberUserId} are optional filters. */
    Page<MemberExit> exits(UUID chamaId, MemberExit.Status status, UUID memberUserId, Pageable pageable);

    /** The member's exit awaiting approval or payout, if any. */
    Optional<MemberExit> inProgress(UUID chamaId, UUID memberUserId);

    boolean referenceExists(UUID chamaId, String method, String reference);
}
