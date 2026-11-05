package ke.chama.shared.domain;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Currency;
import java.util.Objects;
import java.util.stream.IntStream;

/**
 * Immutable monetary amount stored in the currency's minor unit
 * (cents for KES, whole shillings for UGX).
 * <p>
 * Every module passes money as {@code Money}; never as double or a bare BigDecimal.
 * Persist it as {@code BIGINT amount_minor} plus the chama's currency (see ADR-004).
 * Negative amounts are allowed so balances and reversals can be represented.
 */
public record Money(long minorUnits, Currency currency) implements Comparable<Money> {

    public static final Currency KES = Currency.getInstance("KES");

    private static final BigDecimal BASIS_POINTS_DIVISOR = BigDecimal.valueOf(10_000);

    public Money {
        Objects.requireNonNull(currency, "currency must not be null");
    }

    public static Money of(long minorUnits, Currency currency) {
        return new Money(minorUnits, currency);
    }

    public static Money kes(long cents) {
        return new Money(cents, KES);
    }

    public static Money zero(Currency currency) {
        return new Money(0, currency);
    }

    /**
     * Builds Money from a major-unit amount, e.g. {@code 1500.50} KES -> 150050 cents.
     * Rejects amounts with more decimal places than the currency supports.
     */
    public static Money ofMajor(BigDecimal amount, Currency currency) {
        Objects.requireNonNull(amount, "amount must not be null");
        int digits = fractionDigits(currency);
        try {
            BigDecimal minor = amount.setScale(digits, RoundingMode.UNNECESSARY).movePointRight(digits);
            return new Money(minor.longValueExact(), currency);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "Amount " + amount + " has more than " + digits + " decimal places for " + currency, e);
        }
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minorUnits, other.minorUnits), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minorUnits, other.minorUnits), currency);
    }

    public Money negate() {
        return new Money(Math.negateExact(minorUnits), currency);
    }

    public Money times(long factor) {
        return new Money(Math.multiplyExact(minorUnits, factor), currency);
    }

    /**
     * Percentage expressed in basis points (1% = 100 bp, 2% = 200 bp), so rates stay integral.
     * Example: a 2% late penalty on KES 5,000 is {@code kes(500_000).percentage(200, HALF_EVEN)}.
     */
    public Money percentage(long basisPoints, RoundingMode rounding) {
        BigDecimal result = BigDecimal.valueOf(minorUnits)
                .multiply(BigDecimal.valueOf(basisPoints))
                .divide(BASIS_POINTS_DIVISOR, 0, rounding);
        return new Money(result.longValueExact(), currency);
    }

    /**
     * Splits this amount in proportion to {@code ratios} without losing or creating a single minor unit.
     * Uses the largest-remainder method: leftover units go to the shares with the biggest fractional
     * remainder (ties broken by position). The parts always sum exactly to this amount.
     * Used for profit distribution in proportion to member savings.
     */
    public Money[] allocate(long... ratios) {
        if (ratios.length == 0) {
            throw new IllegalArgumentException("At least one ratio is required");
        }
        if (Arrays.stream(ratios).anyMatch(r -> r < 0)) {
            throw new IllegalArgumentException("Ratios must not be negative");
        }
        BigInteger total = Arrays.stream(ratios).mapToObj(BigInteger::valueOf).reduce(BigInteger.ZERO, BigInteger::add);
        if (total.signum() == 0) {
            throw new IllegalArgumentException("Ratios must not all be zero");
        }

        BigInteger amount = BigInteger.valueOf(minorUnits);
        long[] shares = new long[ratios.length];
        BigInteger[] remainders = new BigInteger[ratios.length];
        long distributed = 0;
        for (int i = 0; i < ratios.length; i++) {
            BigInteger[] qr = amount.multiply(BigInteger.valueOf(ratios[i])).divideAndRemainder(total);
            shares[i] = qr[0].longValueExact();
            remainders[i] = qr[1].abs();
            distributed += shares[i];
        }

        long leftover = minorUnits - distributed;
        long step = Long.signum(leftover);
        int[] order = IntStream.range(0, ratios.length).boxed()
                .sorted(Comparator.<Integer, BigInteger>comparing(i -> remainders[i]).reversed()
                        .thenComparing(i -> i))
                .mapToInt(Integer::intValue)
                .toArray();
        for (int k = 0; leftover != 0; k++, leftover -= step) {
            shares[order[k % order.length]] += step;
        }

        return Arrays.stream(shares).mapToObj(s -> new Money(s, currency)).toArray(Money[]::new);
    }

    public boolean isZero() {
        return minorUnits == 0;
    }

    public boolean isPositive() {
        return minorUnits > 0;
    }

    public boolean isNegative() {
        return minorUnits < 0;
    }

    public boolean isGreaterThan(Money other) {
        return compareTo(other) > 0;
    }

    public boolean isLessThan(Money other) {
        return compareTo(other) < 0;
    }

    /** Major-unit value for display/reporting only, e.g. 150050 cents -> 1500.50. */
    public BigDecimal toMajor() {
        return BigDecimal.valueOf(minorUnits, fractionDigits(currency));
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(minorUnits, other.minorUnits);
    }

    @Override
    public String toString() {
        return currency.getCurrencyCode() + " " + toMajor().toPlainString();
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other must not be null");
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Currency mismatch: " + currency + " vs " + other.currency);
        }
    }

    private static int fractionDigits(Currency currency) {
        return Math.max(currency.getDefaultFractionDigits(), 0);
    }
}
