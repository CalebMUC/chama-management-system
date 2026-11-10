/**
 * Payments members report themselves: a contribution, a penalty payment or a loan repayment they
 * made (typically by M-Pesa), with its transaction code. An official holding the matching record
 * permission (the treasurer by default) checks it against the statement and approves it, which
 * records the payment through the owning module exactly as if they had entered it by hand, or
 * rejects it with a reason. Nobody reviews their own report.
 */
@ApplicationModule(id = "submission", displayName = "Payment submissions")
package ke.chama.modules.submission;

import org.springframework.modulith.ApplicationModule;
