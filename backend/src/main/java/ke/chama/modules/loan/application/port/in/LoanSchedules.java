package ke.chama.modules.loan.application.port.in;

import java.util.UUID;

/**
 * Every loan still being repaid, with its schedule: what each borrower still owes, what is overdue
 * and when the next instalment is due. For officials following up repayments, and for checking
 * loans brought over from the chama's records.
 */
public interface LoanSchedules {

    /** An .xlsx workbook: a Summary sheet (one row per loan) and a Schedules sheet (one row per instalment). */
    byte[] export(UUID chamaId);
}
