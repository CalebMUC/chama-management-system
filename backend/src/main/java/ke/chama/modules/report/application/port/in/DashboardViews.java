package ke.chama.modules.report.application.port.in;

import ke.chama.modules.report.domain.model.LoanAging.BucketTotal;
import ke.chama.modules.transaction.api.Ledger.EntrySummary;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/**
 * Dashboard read models: the figures that matter at a glance. Amounts are minor units (cents) of
 * {@code currency}; rates are basis points (1250 = 12.5%). A section is null when the chama's
 * licence does not include the feature it is about (e.g. no loans section without LOANS), so a
 * client shows only what the chama uses.
 */
public final class DashboardViews {

    private DashboardViews() {
    }

    // ---- the chama (officials) ---------------------------------------------------------------------

    /**
     * @param features            the licence features the chama has, e.g. CONTRIBUTIONS, LOANS
     * @param keyFigures          the headline numbers, for a KPI row
     * @param penaltiesOutstandingMinor same as {@code penalties.outstandingMinor}; kept for older clients
     * @param topMembers          the best contributors this year (see {@link TopMember})
     * @param topInvestments      the best-performing investments by return (see {@link InvestmentPerformance})
     * @param trends              the last six months, oldest first
     */
    public record ChamaDashboardView(String chamaName,
                                     LocalDate asOf,
                                     String currency,
                                     List<String> features,
                                     KeyFigures keyFigures,
                                     MembersSection members,
                                     CashSection cash,
                                     SavingsSection savings,
                                     ContributionsSection contributions,
                                     LoansSection loans,
                                     Long penaltiesOutstandingMinor,
                                     PenaltiesSection penalties,
                                     InvestmentsSection investments,
                                     List<TopMember> topMembers,
                                     List<InvestmentPerformance> topInvestments,
                                     PerformanceSection thisMonth,
                                     PerformanceSection yearToDate,
                                     List<MonthTrend> trends,
                                     PendingSection pending,
                                     List<EntrySummary> recentEntries,
                                     Instant generatedAt) {
    }

    // ---- the chama (every member) ------------------------------------------------------------------

    /**
     * How the chama as a whole is doing, for every member: the same chama-wide totals as the
     * officials' dashboard, but nothing that names or singles out a member (no top contributors,
     * no list of who owes penalties, no ledger entries, no work queue).
     *
     * @param penalties      chama-wide totals; {@code topOwing} is always empty here
     * @param investments    null when the chama lacks INVESTMENTS or the viewer lacks INVESTMENT_VIEW
     *                       (as are {@code keyFigures.investmentsMarketValueMinor} and {@code topInvestments})
     * @param trends         the last six months, oldest first
     */
    public record ChamaOverviewView(String chamaName,
                                    LocalDate asOf,
                                    String currency,
                                    List<String> features,
                                    KeyFigures keyFigures,
                                    MembersSection members,
                                    CashSection cash,
                                    SavingsSection savings,
                                    ContributionsSection contributions,
                                    LoansSection loans,
                                    PenaltiesSection penalties,
                                    InvestmentsSection investments,
                                    List<InvestmentPerformance> topInvestments,
                                    PerformanceSection thisMonth,
                                    PerformanceSection yearToDate,
                                    List<MonthTrend> trends,
                                    Instant generatedAt) {
    }

    /**
     * The headline numbers. A figure is null when the licence lacks its feature.
     *
     * @param totalContributionsMinor       every contribution recorded through the system (not reversed)
     * @param contributionsYearToDateMinor  contributions paid since 1 January
     * @param totalAssetsMinor              everything the chama owns, from the ledger
     * @param netWorthMinor                 assets less liabilities (member savings are a liability)
     */
    public record KeyFigures(Long totalContributionsMinor,
                             Long contributionsYearToDateMinor,
                             Long totalSavingsMinor,
                             long cashMinor,
                             Long loansOutstandingMinor,
                             Long investmentsMarketValueMinor,
                             Long penaltiesOutstandingMinor,
                             long totalAssetsMinor,
                             long netWorthMinor) {
    }

    /** @param joinedThisYear members whose membership was approved since 1 January */
    public record MembersSection(long active, long suspended, long pendingApplications, int joinedThisMonth,
                                 int joinedThisYear) {
    }

    /** Money the chama holds right now. */
    public record CashSection(long cashOnHandMinor, long bankMinor, long mobileMoneyMinor, long totalMinor) {
    }

    public record SavingsSection(long totalMinor, List<ProductBalance> products) {
    }

    public record ProductBalance(UUID productId, String code, String name, long balanceMinor) {
    }

    /**
     * The latest contribution period, arrears across all periods, and what was paid in.
     *
     * @param collectionRateBasisPoints  collected per 10,000 expected for the current period
     * @param totalCollectedMinor        every recorded contribution, ever
     * @param complianceRateBasisPoints  across every period due so far and every member: the share of
     *                                   (member, period) dues fully paid
     */
    public record ContributionsSection(String currentPeriod,
                                       LocalDate dueDate,
                                       long expectedMinor,
                                       long collectedMinor,
                                       long collectionRateBasisPoints,
                                       int membersPaid,
                                       int membersLate,
                                       long arrearsMinor,
                                       int membersInArrears,
                                       long totalCollectedMinor,
                                       long collectedThisMonthMinor,
                                       long collectedYearToDateMinor,
                                       int paymentsThisMonth,
                                       long complianceRateBasisPoints) {
    }

    /**
     * @param par30BasisPoints     outstanding on loans over 30 days overdue, per 10,000 outstanding
     * @param aging                active loans by how long they are overdue
     * @param disbursedYearToDateMinor principal paid out since 1 January
     * @param totalDisbursedMinor  principal of every loan ever paid out (active and repaid)
     * @param totalRepaidMinor     repaid on those loans so far
     * @param interestExpectedMinor flat interest on active loans (earned as they are repaid)
     * @param dueNext7DaysMinor    instalments falling due in the next seven days
     */
    public record LoansSection(int activeLoans,
                               long outstandingMinor,
                               long arrearsMinor,
                               int loansInArrears,
                               long par30BasisPoints,
                               int awaitingApproval,
                               int awaitingDisbursement,
                               List<BucketTotal> aging,
                               int disbursedYearToDate,
                               long disbursedYearToDateMinor,
                               long totalDisbursedMinor,
                               long totalRepaidMinor,
                               int repaidLoans,
                               long interestExpectedMinor,
                               int dueNext7Days,
                               long dueNext7DaysMinor) {
    }

    /**
     * Penalties owed now, and this year's assessments, collections and waivers.
     *
     * @param topOwing members owing the most, most first
     */
    public record PenaltiesSection(long outstandingMinor,
                                   int outstandingCount,
                                   int membersOwing,
                                   long assessedYearToDateMinor,
                                   long lateContributionYearToDateMinor,
                                   long finesYearToDateMinor,
                                   long collectedYearToDateMinor,
                                   long waivedYearToDateMinor,
                                   List<MemberAmount> topOwing) {
    }

    public record MemberAmount(UUID memberUserId, String name, Integer memberNumber, long amountMinor, int count) {
    }

    /**
     * Active investments, and the return across everything ever funded.
     *
     * @param totalReturnMinor  profit across active and closed investments (negative for a loss)
     * @param returnBasisPoints that profit per 10,000 invested
     * @param maturingIn30Days  active investments maturing in the next 30 days
     */
    public record InvestmentsSection(int active,
                                     long investedMinor,
                                     long marketValueMinor,
                                     long incomeMinor,
                                     long bookValueMinor,
                                     long unrealisedGainMinor,
                                     long totalReturnMinor,
                                     long returnBasisPoints,
                                     int profitable,
                                     int closed,
                                     int maturingIn30Days) {
    }

    /**
     * One funded investment and how it is doing. Clients link it to the investment's page
     * ({@code /investments/{id}}), where its transactions and valuations are.
     *
     * @param rank                        1 for the best return
     * @param totalReturnMinor            income less expenses, plus the gain in value
     * @param annualisedReturnBasisPoints null when held under 30 days
     */
    public record InvestmentPerformance(int rank,
                                        UUID investmentId,
                                        String name,
                                        String type,
                                        String institution,
                                        String status,
                                        long investedMinor,
                                        long marketValueMinor,
                                        long incomeMinor,
                                        long expensesMinor,
                                        long totalReturnMinor,
                                        int returnBasisPoints,
                                        Integer annualisedReturnBasisPoints,
                                        long daysHeld,
                                        boolean profitable,
                                        LocalDate fundedOn,
                                        LocalDate maturityDate) {
    }

    /**
     * A member ranked by what they contributed in the window, then by how many of their dues they
     * have paid in full.
     *
     * @param contributedMinor          paid in the window (by payment date)
     * @param complianceRateBasisPoints dues fully paid per 10,000 dues so far (all periods)
     * @param savingsMinor              what they hold in all savings products now
     */
    public record TopMember(int rank,
                            UUID memberUserId,
                            String name,
                            Integer memberNumber,
                            long contributedMinor,
                            int payments,
                            long complianceRateBasisPoints,
                            long arrearsMinor,
                            int latePeriods,
                            long savingsMinor) {
    }

    /** The members ranking for a window, with the window it covers. */
    public record TopMembersView(LocalDate from, LocalDate to, String currency, List<TopMember> members) {
    }

    public record TopInvestmentsView(String currency, List<InvestmentPerformance> investments) {
    }

    /**
     * One calendar month.
     *
     * @param contributionsMinor   contributions paid that month; null without CONTRIBUTIONS
     * @param loansDisbursedMinor  principal paid out that month; null without LOANS
     * @param surplusMinor         income less expenses; negative is a deficit
     */
    public record MonthTrend(YearMonth month,
                             Long contributionsMinor,
                             Integer contributionPayments,
                             Long loansDisbursedMinor,
                             long incomeMinor,
                             long expensesMinor,
                             long surplusMinor) {
    }

    public record TrendsView(String currency, List<MonthTrend> months) {
    }

    /** @param surplusMinor income less expenses; negative is a deficit */
    public record PerformanceSection(LocalDate from, LocalDate to, long incomeMinor, long expensesMinor,
                                     long surplusMinor) {
    }

    /**
     * Things waiting for an official; null where the feature is not licensed.
     *
     * @param paymentsAwaitingReview payments members reported, waiting for the treasurer to confirm
     */
    public record PendingSection(long membershipApplications,
                                 Integer loansAwaitingApproval,
                                 Integer loansAwaitingDisbursement,
                                 Long expensesAwaitingApproval,
                                 Long expensesAwaitingPayment,
                                 Long investmentsAwaitingApproval,
                                 long paymentsAwaitingReview,
                                 long contributionsAwaitingReview,
                                 long penaltyPaymentsAwaitingReview,
                                 long loanRepaymentsAwaitingReview) {
    }

    // ---- one member ---------------------------------------------------------------------------------

    /**
     * @param netPositionMinor          savings less what the member owes on loans and penalties
     * @param penaltiesOutstandingMinor same as {@code penalties.outstandingMinor}; kept for older clients
     * @param topInvestments            the chama's best investments, when the member may see investments
     */
    public record MemberDashboardView(String chamaName,
                                      UUID memberUserId,
                                      String memberName,
                                      Integer memberNumber,
                                      String currency,
                                      List<String> features,
                                      Long netPositionMinor,
                                      MySavingsSection savings,
                                      MyContributionsSection contributions,
                                      MyLoansSection loans,
                                      Long penaltiesOutstandingMinor,
                                      MyPenaltiesSection penalties,
                                      MySubmissionsSection submissions,
                                      List<InvestmentPerformance> topInvestments,
                                      Instant generatedAt) {
    }

    public record MySavingsSection(long totalMinor, List<MyAccount> accounts) {
    }

    public record MyAccount(UUID accountId, String accountNumber, String productCode, String productName,
                            boolean active, long balanceMinor) {
    }

    /**
     * @param currentPeriodStatus       PAID, PARTIALLY_PAID or UNPAID for the latest period; null if none owed
     * @param contributedYearToDateMinor paid since 1 January
     * @param complianceRateBasisPoints dues fully paid per 10,000 dues so far
     * @param rank                      where the member stands among seated members by contributions this
     *                                  year (1 = most); null when they paid nothing this year
     */
    public record MyContributionsSection(long totalPaidMinor,
                                         long arrearsMinor,
                                         long advanceMinor,
                                         int latePeriods,
                                         String currentPeriod,
                                         String currentPeriodStatus,
                                         long contributedYearToDateMinor,
                                         long complianceRateBasisPoints,
                                         Integer rank,
                                         int rankOutOf) {
    }

    /**
     * @param nextDueDate the earliest instalment still unpaid across active loans
     * @param loans       the member's own loans (not rejected or cancelled ones), newest first
     */
    public record MyLoansSection(int activeLoans,
                                 int inProgress,
                                 long outstandingMinor,
                                 long arrearsMinor,
                                 LocalDate nextDueDate,
                                 long nextDueMinor,
                                 int guaranteeRequests,
                                 long totalBorrowedMinor,
                                 long totalRepaidMinor,
                                 List<MyLoan> loans) {
    }

    /**
     * @param repaidBasisPoints repaid per 10,000 of the total due (principal + interest)
     */
    public record MyLoan(UUID loanId,
                         String productName,
                         String status,
                         String purpose,
                         long principalMinor,
                         long interestMinor,
                         long totalDueMinor,
                         long repaidMinor,
                         long outstandingMinor,
                         long arrearsMinor,
                         int daysInArrears,
                         long repaidBasisPoints,
                         int termMonths,
                         LocalDate appliedOn,
                         LocalDate disbursedOn,
                         LocalDate maturityDate,
                         LocalDate nextDueDate,
                         long nextDueMinor) {
    }

    /** @param outstanding penalties still owed, oldest first, each payable through a payment report */
    public record MyPenaltiesSection(long outstandingMinor,
                                     int outstandingCount,
                                     long paidMinor,
                                     long waivedMinor,
                                     List<MyPenalty> outstanding) {
    }

    public record MyPenalty(UUID penaltyId, String kind, String description, LocalDate assessedOn, long amountMinor,
                            long outstandingMinor) {
    }

    /** @param pending payments the member reported that the treasurer has not reviewed yet */
    public record MySubmissionsSection(long pending) {
    }
}
