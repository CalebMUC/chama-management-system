package ke.chama.modules.report.application.port.in;

import ke.chama.modules.transaction.api.Ledger.EntrySummary;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Dashboard read models: the figures that matter at a glance. Amounts are minor units (cents) of
 * {@code currency}. A section is null when the chama's licence does not include the feature it is
 * about (e.g. no loans section without LOANS), so a client shows only what the chama uses.
 */
public final class DashboardViews {

    private DashboardViews() {
    }

    // ---- the chama (officials) ---------------------------------------------------------------------

    /** @param features the licence features the chama has, e.g. CONTRIBUTIONS, LOANS */
    public record ChamaDashboardView(String chamaName,
                                     LocalDate asOf,
                                     String currency,
                                     List<String> features,
                                     MembersSection members,
                                     CashSection cash,
                                     SavingsSection savings,
                                     ContributionsSection contributions,
                                     LoansSection loans,
                                     Long penaltiesOutstandingMinor,
                                     InvestmentsSection investments,
                                     PerformanceSection thisMonth,
                                     PerformanceSection yearToDate,
                                     PendingSection pending,
                                     List<EntrySummary> recentEntries,
                                     Instant generatedAt) {
    }

    public record MembersSection(long active, long suspended, long pendingApplications) {
    }

    /** Money the chama holds right now. */
    public record CashSection(long cashOnHandMinor, long bankMinor, long mobileMoneyMinor, long totalMinor) {
    }

    public record SavingsSection(long totalMinor, List<ProductBalance> products) {
    }

    public record ProductBalance(UUID productId, String code, String name, long balanceMinor) {
    }

    /**
     * The latest contribution period, and arrears across all periods.
     *
     * @param collectionRateBasisPoints collected per 10,000 expected for the current period
     */
    public record ContributionsSection(String currentPeriod,
                                       LocalDate dueDate,
                                       long expectedMinor,
                                       long collectedMinor,
                                       long collectionRateBasisPoints,
                                       int membersPaid,
                                       int membersLate,
                                       long arrearsMinor,
                                       int membersInArrears) {
    }

    /** @param par30BasisPoints outstanding on loans over 30 days overdue, per 10,000 outstanding */
    public record LoansSection(int activeLoans,
                               long outstandingMinor,
                               long arrearsMinor,
                               int loansInArrears,
                               long par30BasisPoints,
                               int awaitingApproval,
                               int awaitingDisbursement) {
    }

    public record InvestmentsSection(int active, long investedMinor, long marketValueMinor, long incomeMinor) {
    }

    /** @param surplusMinor income less expenses; negative is a deficit */
    public record PerformanceSection(LocalDate from, LocalDate to, long incomeMinor, long expensesMinor,
                                     long surplusMinor) {
    }

    /** Things waiting for an official; null where the feature is not licensed. */
    public record PendingSection(long membershipApplications,
                                 Integer loansAwaitingApproval,
                                 Integer loansAwaitingDisbursement,
                                 Long expensesAwaitingApproval,
                                 Long expensesAwaitingPayment,
                                 Long investmentsAwaitingApproval) {
    }

    // ---- one member ---------------------------------------------------------------------------------

    public record MemberDashboardView(String chamaName,
                                      UUID memberUserId,
                                      String memberName,
                                      Integer memberNumber,
                                      String currency,
                                      List<String> features,
                                      MySavingsSection savings,
                                      MyContributionsSection contributions,
                                      MyLoansSection loans,
                                      Long penaltiesOutstandingMinor,
                                      Instant generatedAt) {
    }

    public record MySavingsSection(long totalMinor, List<MyAccount> accounts) {
    }

    public record MyAccount(UUID accountId, String accountNumber, String productCode, String productName,
                            boolean active, long balanceMinor) {
    }

    /** @param currentPeriodStatus PAID, PARTIALLY_PAID or UNPAID for the latest period; null if none owed */
    public record MyContributionsSection(long totalPaidMinor,
                                         long arrearsMinor,
                                         long advanceMinor,
                                         int latePeriods,
                                         String currentPeriod,
                                         String currentPeriodStatus) {
    }

    /** @param nextDueDate the earliest instalment still unpaid across active loans */
    public record MyLoansSection(int activeLoans,
                                 int inProgress,
                                 long outstandingMinor,
                                 long arrearsMinor,
                                 LocalDate nextDueDate,
                                 long nextDueMinor,
                                 int guaranteeRequests) {
    }
}
