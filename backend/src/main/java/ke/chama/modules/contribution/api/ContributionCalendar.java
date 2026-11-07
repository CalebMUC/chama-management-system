package ke.chama.modules.contribution.api;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** When the chama's contribution periods start, for other modules. */
public interface ContributionCalendar {

    /** The due date of the chama's first contribution period in the system; empty if none is open yet. */
    Optional<LocalDate> earliestDueDate(UUID chamaId);
}
