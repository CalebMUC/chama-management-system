/**
 * Members leaving the chama. An official starts an exit, which works out the member's settlement:
 * <ul>
 *   <li>refundable savings (e.g. deposits) are paid out to the member;</li>
 *   <li>what they owe on loans and penalties is first cleared from those refundable savings;</li>
 *   <li>non-refundable balances (e.g. share capital, welfare) are forfeited to the chama;</li>
 *   <li>no dividend is paid for the year they leave.</li>
 * </ul>
 * A quorum of officials approves the settlement (maker-checker: not whoever started it, never the
 * member), then an official pays it out, which posts it to the ledger, closes the member's
 * accounts and ends the membership (EXITED).
 * <p>
 * A member cannot leave while they guarantee another member's loan (the guarantee must first be
 * transferred to another member), while a loan application of theirs is in progress, or while they
 * owe more than their refundable savings.
 */
@ApplicationModule(id = "memberexit", displayName = "Member exits")
package ke.chama.modules.memberexit;

import org.springframework.modulith.ApplicationModule;
