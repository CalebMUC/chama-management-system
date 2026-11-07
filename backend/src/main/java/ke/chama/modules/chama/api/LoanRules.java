package ke.chama.modules.chama.api;

/**
 * The chama's loan policy, from its settings.
 *
 * @param maxMultiplier           a member may borrow at most this many times their savings
 * @param interestBasisPoints     flat interest on the principal for the agreed term (1000 = 10%)
 * @param minMembershipMonths     months of membership before a member may borrow
 * @param guarantorThresholdMinor loans above this amount need guarantors
 * @param guarantorsRequired      how many guarantors such loans need
 * @param approvalQuorum          distinct officials who must approve a loan
 */
public record LoanRules(String currency,
                        int maxMultiplier,
                        int interestBasisPoints,
                        int minMembershipMonths,
                        long guarantorThresholdMinor,
                        int guarantorsRequired,
                        int approvalQuorum) {

    /** How many guarantors a loan of this size needs. */
    public int guarantorsFor(long principalMinor) {
        return principalMinor > guarantorThresholdMinor ? guarantorsRequired : 0;
    }
}
