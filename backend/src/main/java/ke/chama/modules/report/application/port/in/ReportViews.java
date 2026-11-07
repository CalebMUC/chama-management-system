package ke.chama.modules.report.application.port.in;

import ke.chama.modules.contribution.api.ContributionReports.Cell;
import ke.chama.modules.contribution.api.ContributionReports.PeriodColumn;
import ke.chama.modules.contribution.api.ContributionReports.StandingSnapshot;
import ke.chama.modules.loan.api.LoanPortfolio.LoanSnapshot;
import ke.chama.modules.report.domain.model.FinancialStatements.Section;
import ke.chama.modules.report.domain.model.LoanAging.BucketTotal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Report read models. Amounts are minor units (cents) of {@code currency}. */
public final class ReportViews {

    private ReportViews() {
    }

    /** Who a report row is about. */
    public record MemberRef(UUID userId, String name, Integer memberNumber) {
    }

    // ---- financial statements --------------------------------------------------------------------

    /**
     * The balance sheet as at a date: what the chama owns, what it owes its members and others,
     * and its own funds.
     *
     * @param balanced assets equal liabilities plus equity, as they always should
     */
    public record FinancialPositionView(String chamaName,
                                        LocalDate asOf,
                                        String currency,
                                        Section assets,
                                        Section liabilities,
                                        Section equity,
                                        long liabilitiesAndEquityMinor,
                                        boolean balanced,
                                        Instant generatedAt) {
    }

    /** Income and expenses over a period. @param surplusMinor income less expenses; negative is a deficit */
    public record IncomeStatementView(String chamaName,
                                      LocalDate from,
                                      LocalDate to,
                                      String currency,
                                      Section income,
                                      Section expenses,
                                      long surplusMinor,
                                      Instant generatedAt) {
    }

    // ---- cash book --------------------------------------------------------------------------------

    /** Money in and out of the chama's cash, bank and M-Pesa accounts. */
    public record CashBookView(String chamaName,
                               LocalDate from,
                               LocalDate to,
                               String currency,
                               List<CashAccountView> accounts,
                               long openingBalanceMinor,
                               long receiptsMinor,
                               long paymentsMinor,
                               long closingBalanceMinor,
                               Instant generatedAt) {
    }

    public record CashAccountView(String code,
                                  String name,
                                  long openingBalanceMinor,
                                  long receiptsMinor,
                                  long paymentsMinor,
                                  long closingBalanceMinor,
                                  List<CashLineView> lines) {
    }

    /** @param balanceMinor the account's balance after this line */
    public record CashLineView(LocalDate date,
                               long entryNumber,
                               String description,
                               String reference,
                               String sourceType,
                               long receiptMinor,
                               long paymentMinor,
                               long balanceMinor) {
    }

    // ---- contributions --------------------------------------------------------------------------

    /**
     * Every contribution period against every member.
     *
     * @param periods oldest first; each member's {@code cells} line up with them
     */
    public record ContributionReportView(String chamaName,
                                         String currency,
                                         List<PeriodColumn> periods,
                                         List<MemberContributionRow> members,
                                         long expectedMinor,
                                         long collectedMinor,
                                         long arrearsMinor,
                                         Instant generatedAt) {
    }

    /** @param cells one per period; null where the member owed nothing for it */
    public record MemberContributionRow(MemberRef member, StandingSnapshot standing, List<Cell> cells) {
    }

    // ---- loans ----------------------------------------------------------------------------------

    /**
     * The loan book today: active loans aged by days overdue, and loans still in the pipeline.
     *
     * @param par30BasisPoints outstanding on loans over 30 days overdue, per 10,000 outstanding
     */
    public record LoanPortfolioView(String chamaName,
                                    LocalDate asOf,
                                    String currency,
                                    int activeLoans,
                                    long outstandingMinor,
                                    long arrearsMinor,
                                    int loansInArrears,
                                    long par30BasisPoints,
                                    List<BucketTotal> aging,
                                    int awaitingApproval,
                                    int awaitingDisbursement,
                                    long pipelineMinor,
                                    List<LoanRow> loans,
                                    Instant generatedAt) {
    }

    /** @param bucket CURRENT, DAYS_1_30, DAYS_31_60, DAYS_61_90 or OVER_90 */
    public record LoanRow(MemberRef borrower, LoanSnapshot loan, String bucket) {
    }

    // ---- savings ----------------------------------------------------------------------------------

    /**
     * Every member's balance in every savings product.
     *
     * @param products the columns; each member's {@code balances} line up with them
     */
    public record SavingsRegisterView(String chamaName,
                                      String currency,
                                      List<ProductColumn> products,
                                      List<SavingsRow> members,
                                      long totalMinor,
                                      Instant generatedAt) {
    }

    public record ProductColumn(UUID productId, String code, String name, String kind, boolean active,
                                long totalMinor) {
    }

    public record SavingsRow(MemberRef member, List<Long> balances, long totalMinor) {
    }

    // ---- member statement -------------------------------------------------------------------------

    /**
     * Everything about one member's money in the chama.
     *
     * @param netPositionMinor savings less what they owe on loans and penalties
     */
    public record MemberStatementView(String chamaName,
                                      MemberRef member,
                                      LocalDate from,
                                      LocalDate to,
                                      String currency,
                                      long savingsMinor,
                                      long loansOutstandingMinor,
                                      long penaltiesOutstandingMinor,
                                      long netPositionMinor,
                                      StandingSnapshot contributions,
                                      List<AccountStatementView> savings,
                                      List<LoanSnapshot> loans,
                                      AccountStatementView penalties,
                                      Instant generatedAt) {
    }

    /** One account's movements in the period, oldest first. */
    public record AccountStatementView(String accountNumber,
                                       String code,
                                       String name,
                                       long openingBalanceMinor,
                                       long closingBalanceMinor,
                                       List<StatementLineView> lines) {
    }

    public record StatementLineView(LocalDate date,
                                    long entryNumber,
                                    String description,
                                    String reference,
                                    String sourceType,
                                    long debitMinor,
                                    long creditMinor,
                                    long balanceMinor) {
    }
}
