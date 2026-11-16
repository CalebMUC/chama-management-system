package ke.chama.modules.memberexit.domain.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * What a member who leaves takes with them, line by line.
 * <pre>
 *   refundable savings (REFUND lines)
 * − loans outstanding  (LOAN lines, cleared from the refundable savings)
 * − penalties owed     (PENALTY lines, cleared from the refundable savings)
 * = payout to the member
 *
 *   non-refundable savings (FORFEIT lines) stay with the chama
 * </pre>
 * No dividend is paid for the year the member leaves, so there is no dividend line.
 *
 * @param lines REFUND lines in the order debts are cleared from them, then FORFEIT, LOAN, PENALTY
 */
public record Settlement(String currency, List<Line> lines) {

    public enum Kind {
        /** A refundable savings balance (e.g. deposits). */
        REFUND,
        /** A non-refundable savings balance (e.g. share capital, welfare), kept by the chama. */
        FORFEIT,
        /** Outstanding on an active loan. */
        LOAN,
        /** Owed on a penalty. */
        PENALTY
    }

    /**
     * @param sourceId    the savings product, loan or penalty
     * @param accountCode the savings product's member-ledger account (REFUND and FORFEIT lines)
     */
    public record Line(Kind kind, UUID sourceId, String accountCode, String description, long amountMinor) {

        public Line {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(sourceId, "sourceId");
            if (amountMinor <= 0) {
                throw new IllegalArgumentException("A settlement line must have a positive amount");
            }
        }
    }

    /** Money taken from one refundable savings account. */
    public record Draw(String accountCode, long amountMinor) {
    }

    /** A loan or penalty and the savings it is cleared from. */
    public record Offset(Line debt, List<Draw> draws) {
    }

    public Settlement {
        Objects.requireNonNull(currency, "currency");
        lines = List.copyOf(lines);
    }

    public long refundableMinor() {
        return total(Kind.REFUND);
    }

    public long forfeitedMinor() {
        return total(Kind.FORFEIT);
    }

    public long loansMinor() {
        return total(Kind.LOAN);
    }

    public long penaltiesMinor() {
        return total(Kind.PENALTY);
    }

    public long owedMinor() {
        return loansMinor() + penaltiesMinor();
    }

    /** What the member owes beyond their refundable savings; they must pay it before they can leave. */
    public long shortfallMinor() {
        return Math.max(0, owedMinor() - refundableMinor());
    }

    /** What is paid out to the member; zero when debts take all the refundable savings. */
    public long payoutMinor() {
        return Math.max(0, refundableMinor() - owedMinor());
    }

    public List<Line> lines(Kind kind) {
        return lines.stream().filter(l -> l.kind() == kind).toList();
    }

    /**
     * Each loan, then each penalty, cleared from the refundable savings in the order of the REFUND
     * lines. Only meaningful without a shortfall.
     */
    public List<Offset> offsets() {
        Map<String, Long> left = refundable();
        List<Offset> offsets = new ArrayList<>();
        for (Line debt : debts()) {
            long owed = debt.amountMinor();
            List<Draw> draws = new ArrayList<>();
            for (Map.Entry<String, Long> account : left.entrySet()) {
                if (owed == 0) {
                    break;
                }
                long take = Math.min(owed, account.getValue());
                if (take > 0) {
                    draws.add(new Draw(account.getKey(), take));
                    account.setValue(account.getValue() - take);
                    owed -= take;
                }
            }
            if (owed > 0) {
                throw new IllegalStateException("The refundable savings do not cover what the member owes");
            }
            offsets.add(new Offset(debt, draws));
        }
        return offsets;
    }

    /** What is left on each refundable savings account once the debts are cleared: the payout, per account. */
    public List<Draw> payoutDraws() {
        Map<String, Long> left = refundable();
        for (Offset offset : offsets()) {
            offset.draws().forEach(d -> left.merge(d.accountCode(), -d.amountMinor(), Long::sum));
        }
        return left.entrySet().stream().filter(e -> e.getValue() > 0).map(e -> new Draw(e.getKey(), e.getValue())).toList();
    }

    /** The same lines (kind, source and amount), so an approved settlement still holds when it is paid. */
    public boolean sameAs(Settlement other) {
        return currency.equals(other.currency) && key(this).equals(key(other));
    }

    private static List<String> key(Settlement s) {
        return s.lines.stream().map(l -> l.kind() + "/" + l.sourceId() + "/" + l.amountMinor()).sorted().toList();
    }

    private List<Line> debts() {
        List<Line> debts = new ArrayList<>(lines(Kind.LOAN));
        debts.addAll(lines(Kind.PENALTY));
        return debts;
    }

    private Map<String, Long> refundable() {
        Map<String, Long> balances = new LinkedHashMap<>();
        lines(Kind.REFUND).forEach(l -> balances.merge(l.accountCode(), l.amountMinor(), Long::sum));
        return balances;
    }

    private long total(Kind kind) {
        return lines.stream().filter(l -> l.kind() == kind).mapToLong(Line::amountMinor).sum();
    }
}
