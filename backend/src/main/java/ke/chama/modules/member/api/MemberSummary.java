package ke.chama.modules.member.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A seated member (active or suspended): what other modules may know.
 *
 * @param joinedAt when the membership was approved; contributions are owed from then on
 */
public record MemberSummary(UUID membershipId,
                            UUID userId,
                            Integer memberNumber,
                            String firstName,
                            String lastName,
                            String email,
                            boolean active,
                            Instant joinedAt) {

    public String fullName() {
        return (firstName == null ? "" : firstName) + (lastName == null ? "" : " " + lastName);
    }
}
