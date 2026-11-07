package ke.chama.modules.contribution.domain.model;

import ke.chama.modules.contribution.domain.model.PaymentAllocation.Allocation;
import ke.chama.modules.contribution.domain.model.PaymentAllocation.Outstanding;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentAllocationTest {

    private static final UUID DEPOSITS = UUID.randomUUID();
    private static final UUID SHARES = UUID.randomUUID();
    private static final UUID WELFARE = UUID.randomUUID();
    private static final LocalDate OCT_5 = LocalDate.of(2026, 10, 5);
    private static final LocalDate NOV_5 = LocalDate.of(2026, 11, 5);

    @Test
    void theOldestPeriodIsClearedFirstAndWithinItTheLowestPriority() {
        List<Outstanding> owed = List.of(
                due(DEPOSITS, 50, NOV_5, 500_000),
                due(SHARES, 20, NOV_5, 50_000),
                due(WELFARE, 10, NOV_5, 20_000),
                due(DEPOSITS, 50, OCT_5, 200_000));

        // KES 2,000 for October's deposits, then November's welfare (KES 200), shares (KES 500), and KES 300 of deposits.
        List<Allocation> split = PaymentAllocation.allocate(300_000, owed, DEPOSITS);

        assertThat(split).containsExactly(
                new Allocation(DEPOSITS, 230_000),
                new Allocation(WELFARE, 20_000),
                new Allocation(SHARES, 50_000));
    }

    @Test
    void whatIsLeftAfterEveryDueGoesToTheDefaultProduct() {
        List<Allocation> split = PaymentAllocation.allocate(600_000,
                List.of(due(SHARES, 20, NOV_5, 50_000), due(DEPOSITS, 50, NOV_5, 500_000)), DEPOSITS);

        assertThat(split).containsExactlyInAnyOrder(new Allocation(SHARES, 50_000), new Allocation(DEPOSITS, 550_000));
        assertThat(split.stream().mapToLong(Allocation::amountMinor).sum()).isEqualTo(600_000);
    }

    @Test
    void withNothingOwedItAllGoesToTheDefaultProduct() {
        assertThat(PaymentAllocation.allocate(100_000, List.of(), DEPOSITS))
                .containsExactly(new Allocation(DEPOSITS, 100_000));
    }

    @Test
    void anOfficialsOwnSplitMustAddUpAndNameEachProductOnce() {
        assertThat(PaymentAllocation.explicit(150_000,
                List.of(new Allocation(SHARES, 100_000), new Allocation(DEPOSITS, 50_000)))).hasSize(2);

        assertThatThrownBy(() -> PaymentAllocation.explicit(150_000, List.of(new Allocation(SHARES, 100_000))))
                .extracting("code").isEqualTo("CONTRIBUTION_INVALID_PAYMENT");
        assertThatThrownBy(() -> PaymentAllocation.explicit(150_000,
                List.of(new Allocation(SHARES, 100_000), new Allocation(SHARES, 50_000))))
                .hasMessageContaining("only once");
        assertThatThrownBy(() -> PaymentAllocation.explicit(150_000,
                List.of(new Allocation(SHARES, 150_000), new Allocation(DEPOSITS, 0))))
                .hasMessageContaining("greater than zero");
    }

    private static Outstanding due(UUID product, int priority, LocalDate dueDate, long amount) {
        return new Outstanding(product, priority, dueDate, dueDate.withDayOfMonth(1), amount);
    }
}
