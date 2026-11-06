package ke.chama.modules.contribution.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Contributions still unpaid (or part-paid) after their grace days, in the current chama. */
public interface LateContributions {

    /**
     * Every late period of every seated member, brought up to date first. Requires a
     * transaction (periods may be opened).
     */
    List<LateContribution> late(UUID chamaId);

    /**
     * @param outstandingMinor what is still unpaid for that period
     */
    record LateContribution(UUID memberUserId, UUID periodId, String periodKey, LocalDate dueDate,
                            long outstandingMinor, String currency) {
    }
}
