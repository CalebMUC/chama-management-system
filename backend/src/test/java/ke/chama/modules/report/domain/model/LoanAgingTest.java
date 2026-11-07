package ke.chama.modules.report.domain.model;

import ke.chama.modules.report.domain.model.LoanAging.Bucket;
import ke.chama.modules.report.domain.model.LoanAging.BucketTotal;
import ke.chama.modules.report.domain.model.LoanAging.LoanAmounts;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LoanAgingTest {

    @Test
    void daysOverdueFallIntoTheUsualBuckets() {
        assertThat(Bucket.of(0)).isEqualTo(Bucket.CURRENT);
        assertThat(Bucket.of(1)).isEqualTo(Bucket.DAYS_1_30);
        assertThat(Bucket.of(30)).isEqualTo(Bucket.DAYS_1_30);
        assertThat(Bucket.of(31)).isEqualTo(Bucket.DAYS_31_60);
        assertThat(Bucket.of(90)).isEqualTo(Bucket.DAYS_61_90);
        assertThat(Bucket.of(91)).isEqualTo(Bucket.OVER_90);
        assertThat(Bucket.of(4_000)).isEqualTo(Bucket.OVER_90);
    }

    @Test
    void portfolioAtRiskCountsLoansMoreThanThirtyDaysOverdue() {
        LoanAging.Summary s = LoanAging.of(List.of(
                new LoanAmounts(600_000, 0, 0),
                new LoanAmounts(200_000, 50_000, 12),
                new LoanAmounts(150_000, 100_000, 45),
                new LoanAmounts(50_000, 50_000, 120)));

        assertThat(s.loans()).isEqualTo(4);
        assertThat(s.outstandingMinor()).isEqualTo(1_000_000);
        assertThat(s.arrearsMinor()).isEqualTo(200_000);
        assertThat(s.loansInArrears()).isEqualTo(3);
        assertThat(s.par30BasisPoints()).isEqualTo(2_000);   // (150,000 + 50,000) / 1,000,000 = 20%
        assertThat(s.buckets()).extracting(BucketTotal::loans).containsExactly(1, 1, 1, 0, 1);
        assertThat(s.buckets().get(2).outstandingMinor()).isEqualTo(150_000);
    }

    @Test
    void anEmptyBookIsNotAtRisk() {
        LoanAging.Summary s = LoanAging.of(List.of());

        assertThat(s.par30BasisPoints()).isZero();
        assertThat(s.buckets()).hasSize(5).allMatch(b -> b.loans() == 0);
    }
}
