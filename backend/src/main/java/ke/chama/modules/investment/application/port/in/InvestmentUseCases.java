package ke.chama.modules.investment.application.port.in;

import ke.chama.modules.investment.domain.model.Investment;
import ke.chama.modules.investment.domain.model.InvestmentTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Investments of the caller's chama: proposed, approved by quorum (maker-checker), funded, and
 * tracked through income, withdrawals, valuations and closure. Money movements post to the
 * ledger; every step is audited. Every member can see every investment.
 */
public interface InvestmentUseCases {

    InvestmentView propose(UUID chamaId, UUID actorUserId, Proposal proposal);

    InvestmentView approve(UUID chamaId, UUID actorUserId, UUID investmentId, String note);

    InvestmentView reject(UUID chamaId, UUID actorUserId, UUID investmentId, String reason);

    InvestmentView cancel(UUID chamaId, UUID actorUserId, UUID investmentId);

    InvestmentDetailView fund(UUID chamaId, UUID actorUserId, UUID investmentId, Movement funding);

    InvestmentDetailView deposit(UUID chamaId, UUID actorUserId, UUID investmentId, Movement deposit);

    InvestmentDetailView recordIncome(UUID chamaId, UUID actorUserId, UUID investmentId, Movement income);

    InvestmentDetailView withdraw(UUID chamaId, UUID actorUserId, UUID investmentId, Movement withdrawal);

    InvestmentDetailView close(UUID chamaId, UUID actorUserId, UUID investmentId, Movement proceeds);

    InvestmentDetailView value(UUID chamaId, UUID actorUserId, UUID investmentId, long valueMinor, LocalDate valuedOn,
                               String note);

    InvestmentDetailView investment(UUID chamaId, UUID investmentId);

    Page<InvestmentView> investments(UUID chamaId, Investment.Status status, Investment.Type type, Pageable pageable);

    PortfolioView portfolio(UUID chamaId);

    record Proposal(String name, Investment.Type type, String institution, String description, long amountMinor,
                    Integer expectedReturnBasisPoints, LocalDate maturityDate) {
    }

    /** A movement of money. {@code amountMinor} is ignored for funding, which is always the approved amount. */
    record Movement(Long amountMinor, String method, String reference, LocalDate date, String note) {
    }

    record ApprovalView(UUID approverUserId, String name, String note, Instant approvedAt) {
    }

    /**
     * Amounts are minor units of {@code currency}.
     *
     * @param bookValueMinor      what the books carry: put in + reinvested income - withdrawals
     * @param marketValueMinor    the latest valuation, or the book value if never valued
     * @param unrealisedGainMinor latest valuation - book value; null if never valued
     * @param realisedGainMinor   on closure: proceeds - book value (negative for a loss)
     * @param performance         profit and return once funded (null before): income less the expenses
     *                            charged to it, plus its gain in value
     */
    record InvestmentView(UUID id,
                          String name,
                          Investment.Type type,
                          String institution,
                          String description,
                          long amountMinor,
                          String currency,
                          Integer expectedReturnBasisPoints,
                          LocalDate maturityDate,
                          Investment.Status status,
                          UUID proposedBy,
                          String proposedByName,
                          int requiredApprovals,
                          List<ApprovalView> approvals,
                          UUID rejectedBy,
                          Instant rejectedAt,
                          String rejectionReason,
                          Instant cancelledAt,
                          LocalDate fundedOn,
                          long bookValueMinor,
                          long incomeMinor,
                          long withdrawnMinor,
                          long depositedMinor,
                          long marketValueMinor,
                          Long latestValuationMinor,
                          LocalDate latestValuationOn,
                          Long unrealisedGainMinor,
                          LocalDate closedOn,
                          Long closingProceedsMinor,
                          Long realisedGainMinor,
                          Investment.Performance performance) {
    }

    record TransactionView(UUID id, InvestmentTransaction.Kind kind, long amountMinor, String method, String reference,
                           LocalDate date, String note, UUID ledgerEntryId, UUID recordedBy, Instant recordedAt) {
    }

    record ValuationView(UUID id, long valueMinor, LocalDate valuedOn, String note, UUID recordedBy, Instant recordedAt) {
    }

    record InvestmentDetailView(InvestmentView investment, List<TransactionView> transactions,
                                List<ValuationView> valuations) {
    }

    record TypeTotal(Investment.Type type, int count, long bookValueMinor, long marketValueMinor) {
    }

    /**
     * The portfolio. Values are of active investments; income, expenses and returns are of every
     * funded investment, active or closed.
     *
     * @param investedMinor          everything ever put in (funding plus deposits)
     * @param incomeMinor            income ever received
     * @param expensesMinor          paid expenses charged to investments
     * @param realisedGainMinor      gains less losses on closed investments
     * @param unrealisedGainMinor    latest valuations less book values of active investments
     * @param totalReturnMinor       income - expenses + realised and unrealised gains: the profit
     * @param returnBasisPoints      total return as a share of what was put in
     * @param maturingWithin30Days   active investments that mature in the next 30 days
     */
    record PortfolioView(String currency,
                         int activeCount,
                         long bookValueMinor,
                         long marketValueMinor,
                         long investedMinor,
                         long incomeMinor,
                         long expensesMinor,
                         long realisedGainMinor,
                         long unrealisedGainMinor,
                         long totalReturnMinor,
                         int returnBasisPoints,
                         List<TypeTotal> byType,
                         List<InvestmentView> maturingWithin30Days) {
    }
}
