package ke.chama.modules.contribution.application.port.in;

import ke.chama.modules.contribution.domain.model.ContributionPayment;
import ke.chama.modules.contribution.domain.model.MemberStanding;
import ke.chama.modules.contribution.domain.model.PaymentMethod;
import ke.chama.modules.contribution.domain.model.PeriodSchedule.Frequency;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Read models. Amounts are minor units (cents) of {@code currency}. */
public final class ContributionViews {

    private ContributionViews() {
    }

    public record PaymentView(UUID id,
                              UUID memberUserId,
                              String memberName,
                              Integer memberNumber,
                              long amountMinor,
                              String currency,
                              PaymentMethod method,
                              String reference,
                              LocalDate paidOn,
                              String notes,
                              ContributionPayment.Status status,
                              UUID ledgerEntryId,
                              UUID recordedBy,
                              Instant recordedAt,
                              UUID reversedBy,
                              Instant reversedAt,
                              String reversalReason,
                              List<AllocationView> allocations) {
    }

    /** What part of a payment went into which savings product. */
    public record AllocationView(UUID productId, String productCode, String productName, long amountMinor) {
    }

    /**
     * @param amountDueMinor what each member owes for the period across every product
     * @param products       what each product asks for it
     */
    public record PeriodView(UUID id,
                             String key,
                             Frequency frequency,
                             LocalDate startDate,
                             LocalDate endDate,
                             LocalDate dueDate,
                             long amountDueMinor,
                             String currency,
                             List<PeriodProductView> products) {
    }

    public record PeriodProductView(UUID productId, String productCode, String productName, long amountMinor) {
    }

    /** A member's standing in one savings product. */
    public record ProductStandingView(UUID productId,
                                      String productCode,
                                      String productName,
                                      long totalPaidMinor,
                                      long dueToDateMinor,
                                      long arrearsMinor,
                                      long advanceMinor) {
    }

    /**
     * @param expectedMinor  total owed by all members for the period
     * @param collectedMinor paid towards it (payments clear the oldest dues first)
     */
    public record PeriodSummaryView(PeriodView period,
                                    boolean due,
                                    long expectedMinor,
                                    long collectedMinor,
                                    int members,
                                    int membersPaid,
                                    int membersLate) {
    }

    public record MemberPeriodView(UUID memberUserId,
                                   String memberName,
                                   Integer memberNumber,
                                   long amountDueMinor,
                                   long paidMinor,
                                   MemberStanding.Status status,
                                   boolean late) {
    }

    public record PeriodDetailView(PeriodSummaryView summary, List<MemberPeriodView> members) {
    }

    /**
     * Totals across every savings product, with the breakdown per product.
     *
     * @param balanceMinor paid minus due-to-date: negative means in arrears, positive is credit
     */
    public record StandingView(UUID memberUserId,
                               String memberName,
                               Integer memberNumber,
                               String currency,
                               long totalPaidMinor,
                               long dueToDateMinor,
                               long arrearsMinor,
                               long advanceMinor,
                               long balanceMinor,
                               int latePeriods,
                               List<ProductStandingView> products) {
    }

    public record MyPeriodView(PeriodView period, long amountDueMinor, long paidMinor,
                               MemberStanding.Status status, boolean due, boolean late) {
    }

    /** The caller's own contributions: standing, every period, and their latest payments. */
    public record MyContributionsView(StandingView standing, List<MyPeriodView> periods, List<PaymentView> recentPayments) {
    }
}
