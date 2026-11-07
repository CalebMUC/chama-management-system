package ke.chama.modules.account.api;

import java.util.UUID;

/**
 * A savings product, for other modules.
 *
 * @param kind                  DEPOSITS, SHARE_CAPITAL, WELFARE or CUSTOM
 * @param ledgerAccountCode     the member-ledger account holding the product's balances; post to it by code
 * @param contributionMinor     owed per contribution period by every member with an account; 0 = top-ups only
 * @param allocationPriority    within a period, a payment clears lower numbers first
 * @param mandatory             every seated member has an account
 * @param defaultProduct        takes whatever a payment does not owe elsewhere; always active and mandatory
 */
public record SavingsProductSummary(UUID id,
                                    String code,
                                    String name,
                                    String kind,
                                    String ledgerAccountCode,
                                    long contributionMinor,
                                    int allocationPriority,
                                    boolean mandatory,
                                    boolean countsTowardLoanLimit,
                                    boolean refundableOnExit,
                                    boolean earnsDividend,
                                    boolean defaultProduct,
                                    boolean active) {
}
