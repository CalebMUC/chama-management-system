package ke.chama.modules.member.application.port.out;

import ke.chama.modules.member.domain.model.IdDocumentType;
import ke.chama.modules.member.domain.model.MemberKyc;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** KYC records, one per membership. Every query is also restricted to the current tenant by row-level security. */
public interface MemberKycRepository {

    /** Creates or replaces the membership's record. */
    void save(UUID chamaId, UUID membershipId, MemberKyc kyc, UUID updatedBy, Instant now);

    Optional<StoredKyc> find(UUID chamaId, UUID membershipId);

    /** True if another membership of the chama already holds this document. */
    boolean idDocumentTaken(UUID chamaId, IdDocumentType idType, String idNumber, UUID exceptMembershipId);

    record StoredKyc(UUID membershipId, MemberKyc kyc, UUID updatedBy, Instant updatedAt) {
    }
}
