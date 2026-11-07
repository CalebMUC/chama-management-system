package ke.chama.modules.contribution.domain.model;

import ke.chama.modules.contribution.domain.exception.ContributionExceptions.InvalidPaymentException;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * How one payment is split across the member's savings products.
 * <p>
 * Automatically: the oldest period first and, within a period, the product with the lowest
 * allocation priority first (e.g. welfare, then shares, then deposits), including dues of the
 * current period that are not due yet. Whatever is left after every due is an advance to the
 * default product. An official may instead say exactly how to split it, e.g. a voluntary top-up of
 * share capital.
 */
public final class PaymentAllocation {

    private PaymentAllocation() {
    }

    /** What the payment puts into one product. */
    public record Allocation(UUID productId, long amountMinor) {

        public Allocation {
            Objects.requireNonNull(productId, "productId");
        }
    }

    /** One product's unpaid due for one period. */
    public record Outstanding(UUID productId, int priority, LocalDate dueDate, LocalDate startDate, long amountMinor) {
    }

    private static final Comparator<Outstanding> ORDER = Comparator.comparing(Outstanding::dueDate)
            .thenComparing(Outstanding::startDate)
            .thenComparingInt(Outstanding::priority)
            .thenComparing(Outstanding::productId);

    /** Splits {@code amountMinor} over the outstanding dues; the rest goes to {@code fallbackProductId}. */
    public static List<Allocation> allocate(long amountMinor, Collection<Outstanding> outstanding, UUID fallbackProductId) {
        Objects.requireNonNull(fallbackProductId, "fallbackProductId");
        if (amountMinor <= 0) {
            throw new InvalidPaymentException("The amount must be greater than zero");
        }
        Map<UUID, Long> split = new LinkedHashMap<>();
        long remaining = amountMinor;
        for (Outstanding due : outstanding.stream().sorted(ORDER).toList()) {
            if (remaining == 0) {
                break;
            }
            long paid = Math.min(remaining, Math.max(due.amountMinor(), 0));
            if (paid > 0) {
                split.merge(due.productId(), paid, Long::sum);
                remaining -= paid;
            }
        }
        if (remaining > 0) {
            split.merge(fallbackProductId, remaining, Long::sum);
        }
        return split.entrySet().stream().map(e -> new Allocation(e.getKey(), e.getValue())).toList();
    }

    /**
     * Checks an official's own split: every product named once, every amount positive, and the
     * amounts adding up to the payment. Whether the member may pay into each product is checked
     * by the caller.
     */
    public static List<Allocation> explicit(long amountMinor, List<Allocation> requested) {
        if (requested == null || requested.isEmpty()) {
            throw new InvalidPaymentException("Say how to split the payment, or leave allocations out to split it automatically");
        }
        Set<UUID> seen = new HashSet<>();
        long total = 0;
        for (Allocation a : requested) {
            if (a.amountMinor() <= 0) {
                throw new InvalidPaymentException("Every allocation must be greater than zero");
            }
            if (!seen.add(a.productId())) {
                throw new InvalidPaymentException("Each product can appear only once in the allocations");
            }
            total = Math.addExact(total, a.amountMinor());
        }
        if (total != amountMinor) {
            throw new InvalidPaymentException("The allocations add up to " + total + " but the payment is " + amountMinor);
        }
        return List.copyOf(requested);
    }
}
