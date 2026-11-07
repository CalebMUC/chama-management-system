package ke.chama.modules.account.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A member's account in one savings product, for other modules. Its balance lives in the ledger.
 *
 * @param openedOn dues are owed for periods due on or after this day
 * @param closedOn null while open; no dues for periods due on or after this day
 */
public record MemberAccountSummary(UUID id,
                                   String accountNumber,
                                   UUID memberUserId,
                                   UUID productId,
                                   boolean active,
                                   LocalDate openedOn,
                                   LocalDate closedOn) {

    /** Whether a period due on {@code dueDate} falls within the time this account was open. */
    public boolean owesFor(LocalDate dueDate) {
        return !dueDate.isBefore(openedOn) && (closedOn == null || dueDate.isBefore(closedOn));
    }
}
