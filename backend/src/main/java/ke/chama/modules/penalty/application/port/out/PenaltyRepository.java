package ke.chama.modules.penalty.application.port.out;

import ke.chama.modules.penalty.domain.model.Penalty;
import ke.chama.modules.penalty.domain.model.PenaltyPayment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Row-level security limits every query to the current chama. */
public interface PenaltyRepository {

    void insert(Penalty penalty);

    /** Saves payments and waivers; false if the penalty changed since it was read. */
    boolean update(Penalty penalty);

    Optional<Penalty> penalty(UUID chamaId, UUID penaltyId);

    /** Every filter is optional. Newest first. */
    Page<Penalty> penalties(UUID chamaId, UUID memberUserId, Penalty.Status status, Pageable pageable);

    List<Penalty> penaltiesOf(UUID chamaId, UUID memberUserId);

    /** "memberUserId/sourceKey" of every penalty that has a source, to skip ones already assessed. */
    Set<String> assessedSourceKeys(UUID chamaId);

    void insertPayment(PenaltyPayment payment);

    List<PenaltyPayment> payments(UUID chamaId, UUID penaltyId);

    boolean referenceExists(UUID chamaId, String method, String reference);

    /**
     * Owed now, plus assessed / collected / waived between the two days (inclusive). Waivers are
     * dated by their time in {@code zone}, e.g. Africa/Nairobi.
     */
    ke.chama.modules.penalty.api.PenaltyReports.PenaltySummary summary(UUID chamaId, java.time.LocalDate from,
                                                                       java.time.LocalDate to, String zone, int topOwing);
}
