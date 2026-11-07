package ke.chama.modules.investment.domain.model;

import ke.chama.modules.investment.domain.exception.InvestmentExceptions.InvalidInvestmentException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Money moving into or out of an investment, posted to the ledger.
 *
 * @param method CASH, BANK or MPESA (the chama account the money left or came into), or
 *               REINVESTED for income that stays in the investment
 */
public record InvestmentTransaction(UUID id,
                                    UUID chamaId,
                                    UUID investmentId,
                                    Kind kind,
                                    long amountMinor,
                                    String currency,
                                    String method,
                                    String reference,
                                    LocalDate date,
                                    String note,
                                    UUID ledgerEntryId,
                                    UUID recordedBy,
                                    Instant recordedAt) {

    public static final String REINVESTED = "REINVESTED";
    private static final Set<String> METHODS = Set.of("CASH", "BANK", "MPESA", REINVESTED);

    public enum Kind {
        /** The money first put in. */
        FUNDING,
        /** More money put in later, e.g. a monthly top-up. */
        DEPOSIT,
        /** Interest, dividends or rent. */
        INCOME,
        /** Part of the money taken out. */
        WITHDRAWAL,
        /** Sold or redeemed: the proceeds. */
        CLOSURE
    }

    static InvestmentTransaction record(Investment investment, Kind kind, long amount, String method, String reference,
                                        LocalDate date, String note, UUID by, LocalDate today, Instant now) {
        if (method == null || !METHODS.contains(method)) {
            throw new InvalidInvestmentException("method must be CASH, BANK, MPESA or REINVESTED");
        }
        if (kind == Kind.FUNDING && REINVESTED.equals(method)) {
            throw new InvalidInvestmentException("An investment is funded from CASH, BANK or MPESA");
        }
        LocalDate on = date == null ? today : date;
        if (on.isAfter(today)) {
            throw new InvalidInvestmentException("A transaction cannot be dated in the future");
        }
        if (investment.fundedOn() != null && on.isBefore(investment.fundedOn())) {
            throw new InvalidInvestmentException("A transaction cannot be dated before the investment was funded");
        }
        String ref = reference == null || reference.isBlank() ? null : reference.trim().toUpperCase(Locale.ROOT);
        if (ref == null && (method.equals("BANK") || method.equals("MPESA")) && amount > 0) {
            throw new InvalidInvestmentException(method + " transactions need their reference (transaction code or slip number)");
        }
        if (ref != null && ref.length() > 100) {
            throw new InvalidInvestmentException("reference must be at most 100 characters");
        }
        return new InvestmentTransaction(UUID.randomUUID(), investment.chamaId(), investment.id(), kind, amount,
                investment.currency(), method, ref, on, Investment.optional(note, Investment.MAX_TEXT_LENGTH, "note"),
                null, by, now);
    }

    public boolean reinvested() {
        return REINVESTED.equals(method);
    }

    public InvestmentTransaction postedAs(UUID entryId) {
        return new InvestmentTransaction(id, chamaId, investmentId, kind, amountMinor, currency, method, reference, date,
                note, entryId, recordedBy, recordedAt);
    }
}
