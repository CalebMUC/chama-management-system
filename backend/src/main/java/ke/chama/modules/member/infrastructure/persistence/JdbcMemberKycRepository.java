package ke.chama.modules.member.infrastructure.persistence;

import ke.chama.modules.member.application.port.out.MemberKycRepository;
import ke.chama.modules.member.domain.exception.MemberExceptions.IdDocumentTakenException;
import ke.chama.modules.member.domain.model.Gender;
import ke.chama.modules.member.domain.model.IdDocumentType;
import ke.chama.modules.member.domain.model.MemberKyc;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** Plain JDBC: one flat row per membership, no relationships to map. */
@Repository
class JdbcMemberKycRepository implements MemberKycRepository {

    private final JdbcTemplate jdbc;

    JdbcMemberKycRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(UUID chamaId, UUID membershipId, MemberKyc kyc, UUID updatedBy, Instant now) {
        try {
            jdbc.update("""
                            insert into member.member_kyc (membership_id, tenant_id, id_type, id_number, date_of_birth, gender,
                                                           county, town, physical_address, updated_by, updated_at)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            on conflict (membership_id) do update
                               set id_type = excluded.id_type, id_number = excluded.id_number,
                                   date_of_birth = excluded.date_of_birth, gender = excluded.gender,
                                   county = excluded.county, town = excluded.town,
                                   physical_address = excluded.physical_address,
                                   updated_by = excluded.updated_by, updated_at = excluded.updated_at""",
                    membershipId, chamaId, kyc.idType().name(), kyc.idNumber(), kyc.dateOfBirth(), kyc.gender().name(),
                    kyc.county(), kyc.town(), kyc.physicalAddress(), updatedBy, Timestamp.from(now));
        } catch (DuplicateKeyException e) {
            // Lost a race with another member saving the same document.
            throw new IdDocumentTakenException();
        }
    }

    @Override
    public Optional<StoredKyc> find(UUID chamaId, UUID membershipId) {
        return jdbc.query("""
                        select membership_id, id_type, id_number, date_of_birth, gender, county, town, physical_address,
                               updated_by, updated_at
                          from member.member_kyc
                         where tenant_id = ? and membership_id = ?""",
                (rs, n) -> new StoredKyc(
                        rs.getObject("membership_id", UUID.class),
                        new MemberKyc(IdDocumentType.valueOf(rs.getString("id_type")), rs.getString("id_number"),
                                rs.getObject("date_of_birth", LocalDate.class), Gender.valueOf(rs.getString("gender")),
                                rs.getString("county"), rs.getString("town"), rs.getString("physical_address")),
                        rs.getObject("updated_by", UUID.class),
                        rs.getTimestamp("updated_at").toInstant()),
                chamaId, membershipId).stream().findFirst();
    }

    @Override
    public boolean idDocumentTaken(UUID chamaId, IdDocumentType idType, String idNumber, UUID exceptMembershipId) {
        Boolean taken = jdbc.queryForObject("""
                        select exists (select 1 from member.member_kyc
                                        where tenant_id = ? and id_type = ? and id_number = ?
                                          and (?::uuid is null or membership_id <> ?::uuid))""",
                Boolean.class, chamaId, idType.name(), idNumber, exceptMembershipId, exceptMembershipId);
        return Boolean.TRUE.equals(taken);
    }
}
