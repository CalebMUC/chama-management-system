package ke.chama.modules.account.domain.model;

import ke.chama.modules.account.domain.exception.AccountExceptions.AccountStateException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * A member's account in one savings product, e.g. "SHR-0007" for member 7's share capital. The
 * balance is never stored: it is the product's ledger account balance for the member.
 * <pre>
 * open ──► ACTIVE ──close (zero balance)──► CLOSED ──reopen──► ACTIVE
 * </pre>
 * Dues are owed for periods due from {@code openedOn}; closing stops new dues (what is already
 * owed stays owed). Reopening starts dues again from the day it is reopened.
 */
public record MemberAccount(UUID id,
                            UUID chamaId,
                            String accountNumber,
                            UUID memberUserId,
                            UUID productId,
                            Status status,
                            LocalDate openedOn,
                            UUID openedBy,
                            LocalDate closedOn,
                            UUID closedBy,
                            String closureReason,
                            Instant createdAt,
                            long version) {

    static final int MAX_REASON_LENGTH = 300;

    public enum Status {
        ACTIVE,
        CLOSED
    }

    public MemberAccount {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(chamaId, "chamaId");
        Objects.requireNonNull(memberUserId, "memberUserId");
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(openedOn, "openedOn");
    }

    /** @param openedBy null when the system opens it (a mandatory product) */
    public static MemberAccount open(UUID chamaId, String productCode, Integer memberNumber, UUID memberUserId,
                                     UUID productId, LocalDate openedOn, UUID openedBy, Instant now) {
        return new MemberAccount(UUID.randomUUID(), chamaId, number(productCode, memberNumber, memberUserId),
                memberUserId, productId, Status.ACTIVE, openedOn, openedBy, null, null, null, now, 0);
    }

    /** The product code and the member number, e.g. "SHR-0007"; the start of the user id without a number. */
    public static String number(String productCode, Integer memberNumber, UUID memberUserId) {
        String suffix = memberNumber == null ? memberUserId.toString().substring(0, 8) : "%04d".formatted(memberNumber);
        return productCode + "-" + suffix;
    }

    public boolean active() {
        return status == Status.ACTIVE;
    }

    /**
     * Closes the account. The caller checks that the balance is zero and that the product does
     * not require every member to have an account.
     */
    public MemberAccount close(UUID by, String reason, LocalDate today) {
        if (!active()) {
            throw new AccountStateException("Account " + accountNumber + " is already closed");
        }
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty() || why.length() > MAX_REASON_LENGTH) {
            throw new AccountStateException("A reason is required (at most " + MAX_REASON_LENGTH + " characters)");
        }
        return new MemberAccount(id, chamaId, accountNumber, memberUserId, productId, Status.CLOSED, openedOn, openedBy,
                today, by, why, createdAt, version);
    }

    /** Opens a closed account again; dues start from {@code today}. */
    public MemberAccount reopen(UUID by, LocalDate today) {
        if (active()) {
            throw new AccountStateException("Account " + accountNumber + " is already open");
        }
        return new MemberAccount(id, chamaId, accountNumber, memberUserId, productId, Status.ACTIVE, today, by,
                null, null, null, createdAt, version);
    }
}
