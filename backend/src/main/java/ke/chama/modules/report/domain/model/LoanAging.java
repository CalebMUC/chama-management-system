package ke.chama.modules.report.domain.model;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Active loans grouped by how long their oldest unpaid instalment has been overdue, the usual way a
 * SACCO or chama judges the health of its loan book.
 * <p>
 * Portfolio at risk (PAR30) is the share of everything owed on active loans that sits in loans more
 * than 30 days overdue, in basis points (1250 = 12.5%).
 */
public final class LoanAging {

    public enum Bucket {
        CURRENT("Up to date", 0, 0),
        DAYS_1_30("1-30 days overdue", 1, 30),
        DAYS_31_60("31-60 days overdue", 31, 60),
        DAYS_61_90("61-90 days overdue", 61, 90),
        OVER_90("Over 90 days overdue", 91, Integer.MAX_VALUE);

        private final String label;
        private final int fromDays;
        private final int toDays;

        Bucket(String label, int fromDays, int toDays) {
            this.label = label;
            this.fromDays = fromDays;
            this.toDays = toDays;
        }

        public String label() {
            return label;
        }

        public static Bucket of(int daysInArrears) {
            for (Bucket b : values()) {
                if (daysInArrears >= b.fromDays && daysInArrears <= b.toDays) {
                    return b;
                }
            }
            return CURRENT;
        }
    }

    /** One active loan's amounts. */
    public record LoanAmounts(long outstandingMinor, long arrearsMinor, int daysInArrears) {
    }

    public record BucketTotal(Bucket bucket, String label, int loans, long outstandingMinor, long arrearsMinor) {
    }

    /** @param par30BasisPoints outstanding on loans over 30 days overdue, per 10,000 of everything outstanding */
    public record Summary(int loans, long outstandingMinor, long arrearsMinor, int loansInArrears,
                          long par30BasisPoints, List<BucketTotal> buckets) {
    }

    private LoanAging() {
    }

    public static Summary of(List<LoanAmounts> activeLoans) {
        Map<Bucket, long[]> totals = new EnumMap<>(Bucket.class);
        for (Bucket b : Bucket.values()) {
            totals.put(b, new long[3]);
        }
        long outstanding = 0;
        long arrears = 0;
        long atRisk = 0;
        int inArrears = 0;
        for (LoanAmounts loan : activeLoans) {
            Bucket bucket = Bucket.of(loan.daysInArrears());
            long[] t = totals.get(bucket);
            t[0]++;
            t[1] += loan.outstandingMinor();
            t[2] += loan.arrearsMinor();
            outstanding += loan.outstandingMinor();
            arrears += loan.arrearsMinor();
            if (loan.daysInArrears() > 0) {
                inArrears++;
            }
            if (loan.daysInArrears() > 30) {
                atRisk += loan.outstandingMinor();
            }
        }
        List<BucketTotal> buckets = new ArrayList<>();
        totals.forEach((b, t) -> buckets.add(new BucketTotal(b, b.label(), (int) t[0], t[1], t[2])));
        long par30 = outstanding == 0 ? 0 : Math.round(atRisk * 10_000.0 / outstanding);
        return new Summary(activeLoans.size(), outstanding, arrears, inArrears, par30, List.copyOf(buckets));
    }
}
