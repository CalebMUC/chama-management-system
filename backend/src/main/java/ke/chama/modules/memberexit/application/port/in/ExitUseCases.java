package ke.chama.modules.memberexit.application.port.in;

import ke.chama.modules.memberexit.domain.model.MemberExit;
import ke.chama.modules.memberexit.domain.model.Settlement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Members leaving the caller's chama: preview the settlement, start the exit, approve it by quorum,
 * pay it out. Amounts are minor units of {@code currency}; every step is audit-logged.
 */
public interface ExitUseCases {

    /** The member's settlement as it stands today, and anything that stops them leaving now. */
    ExitPreviewView preview(UUID chamaId, UUID memberUserId);

    ExitView request(UUID chamaId, UUID actorUserId, UUID memberUserId, String reason);

    ExitView approve(UUID chamaId, UUID actorUserId, UUID exitId, String note);

    ExitView reject(UUID chamaId, UUID actorUserId, UUID exitId, String reason);

    ExitView cancel(UUID chamaId, UUID actorUserId, UUID exitId);

    ExitView settle(UUID chamaId, UUID actorUserId, UUID exitId, Payout payout);

    ExitView exit(UUID chamaId, UUID exitId);

    Page<ExitView> exits(UUID chamaId, MemberExit.Status status, UUID memberUserId, Pageable pageable);

    /** The caller's own exits, newest first. */
    List<ExitView> mine(UUID chamaId, UUID memberUserId);

    /**
     * How the payout leaves the chama. Only needed when something is paid out.
     *
     * @param method CASH, BANK or MPESA
     */
    record Payout(String method, String reference, LocalDate settledOn) {
    }

    /** @param kind REFUND, FORFEIT, LOAN or PENALTY */
    record LineView(Settlement.Kind kind, UUID sourceId, String description, long amountMinor) {
    }

    /**
     * The settlement in figures.
     *
     * @param refundableMinor savings paid back (before what the member owes is taken off)
     * @param forfeitedMinor  non-refundable savings the chama keeps
     * @param loansMinor      outstanding on the member's loans, cleared from refundable savings
     * @param penaltiesMinor  owed on penalties, cleared from refundable savings
     * @param payoutMinor     paid to the member
     * @param shortfallMinor  what the member owes beyond their refundable savings (blocks the exit)
     * @param dividendMinor   always zero: no dividend is paid for the year a member leaves
     */
    record SettlementView(String currency, long refundableMinor, long forfeitedMinor, long loansMinor,
                          long penaltiesMinor, long payoutMinor, long shortfallMinor, long dividendMinor,
                          List<LineView> lines) {
    }

    /** @param blockers why the member cannot leave yet; empty when an exit can be started */
    record ExitPreviewView(UUID memberUserId, String memberName, Integer memberNumber, SettlementView settlement,
                           List<String> blockers, boolean canExit) {
    }

    record ApprovalView(UUID approverUserId, String name, String note, Instant approvedAt) {
    }

    record ExitView(UUID id,
                    UUID memberUserId,
                    String memberName,
                    Integer memberNumber,
                    String reason,
                    MemberExit.Status status,
                    SettlementView settlement,
                    UUID requestedBy,
                    String requestedByName,
                    Instant requestedAt,
                    int requiredApprovals,
                    List<ApprovalView> approvals,
                    UUID rejectedBy,
                    Instant rejectedAt,
                    String rejectionReason,
                    Instant cancelledAt,
                    LocalDate settledOn,
                    UUID settledBy,
                    String paymentMethod,
                    String paymentReference,
                    UUID settlementEntryId) {
    }
}
