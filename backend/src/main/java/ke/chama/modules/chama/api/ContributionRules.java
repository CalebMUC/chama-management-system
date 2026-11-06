package ke.chama.modules.chama.api;

/**
 * The chama's contribution rules, from its settings.
 *
 * @param frequency              MONTHLY or WEEKLY
 * @param dueDay                 day of the month (1-28) or of the week (1 = Monday ... 7 = Sunday)
 * @param lateGraceDays          days after the due date before a contribution counts as late
 * @param latePenaltyBasisPoints penalty on the unpaid amount of a late contribution (200 = 2%)
 */
public record ContributionRules(long amountMinor,
                                String currency,
                                String frequency,
                                int dueDay,
                                int lateGraceDays,
                                int latePenaltyBasisPoints) {

    public static final String MONTHLY = "MONTHLY";
    public static final String WEEKLY = "WEEKLY";
}
