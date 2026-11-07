package ke.chama.modules.contribution.application.service;

import ke.chama.modules.account.api.MemberAccountSummary;
import ke.chama.modules.account.api.MemberAccounts;
import ke.chama.modules.account.api.SavingsProductSummary;
import ke.chama.modules.account.api.SavingsProducts;
import ke.chama.modules.chama.api.ChamaDirectory;
import ke.chama.modules.chama.api.ContributionRules;
import ke.chama.modules.contribution.application.port.out.ContributionRepository;
import ke.chama.modules.contribution.application.port.out.ContributionRepository.DueRow;
import ke.chama.modules.contribution.domain.model.ContributionPeriod;
import ke.chama.modules.contribution.domain.model.PeriodSchedule;
import ke.chama.modules.contribution.domain.model.PeriodSchedule.Frequency;
import ke.chama.modules.contribution.domain.model.PeriodSchedule.PeriodSpan;
import ke.chama.modules.member.api.MemberDirectory;
import ke.chama.modules.member.api.MemberSummary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Keeps a chama's periods and dues up to date. Idempotent and cheap, so it runs at the start of
 * every contribution operation instead of relying on a scheduled job that could miss a cycle:
 * <ol>
 *     <li>opens every period from the last one up to the one containing today (a chama's first
 *     period is the current one), each with what every active savings product asks at that
 *     moment (later changes apply to later periods);</li>
 *     <li>opens the accounts every seated member must have (mandatory products);</li>
 *     <li>gives every seated member a due per period and product for each of their accounts that
 *     was open on the period's due date. Existing dues are never changed.</li>
 * </ol>
 */
@Component
class PeriodOpener {

    /** Guards against runaway catch-up, e.g. a clock far in the future: about 20 years of months. */
    static final int MAX_PERIODS_PER_CATCH_UP = 260;

    private final ContributionRepository repository;
    private final ChamaDirectory chamas;
    private final MemberDirectory members;
    private final SavingsProducts products;
    private final MemberAccounts accounts;
    private final Clock clock;

    PeriodOpener(ContributionRepository repository, ChamaDirectory chamas, MemberDirectory members,
                 SavingsProducts products, MemberAccounts accounts, Clock clock) {
        this.repository = repository;
        this.chamas = chamas;
        this.members = members;
        this.products = products;
        this.accounts = accounts;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void bringUpToDate(UUID chamaId, ZoneId zone) {
        ContributionRules rules = chamas.contributionRules(chamaId).orElseThrow();
        LocalDate today = LocalDate.now(clock.withZone(zone));
        Frequency frequency = Frequency.valueOf(rules.frequency());

        Optional<ContributionPeriod> latest = repository.latestPeriod(chamaId);
        PeriodSpan next = latest.isEmpty()
                ? PeriodSchedule.containing(today, frequency, rules.dueDay())
                : PeriodSchedule.after(latest.get().endDate(), frequency, rules.dueDay());
        if (!next.start().isAfter(today)) {
            Map<UUID, Long> asks = amountPerProduct(chamaId, rules);
            long total = asks.values().stream().mapToLong(Long::longValue).sum();
            for (int i = 0; i < MAX_PERIODS_PER_CATCH_UP && !next.start().isAfter(today); i++) {
                repository.insertPeriod(ContributionPeriod.open(chamaId, next, total, rules.currency(), clock.instant()),
                        asks);
                next = PeriodSchedule.after(next.end(), frequency, rules.dueDay());
            }
        }

        accounts.openMandatoryAccounts(chamaId);
        syncDues(chamaId);
    }

    /**
     * What each active product asks per period. The default product always asks the chama's
     * contribution amount, so a period never asks for nothing.
     */
    private Map<UUID, Long> amountPerProduct(UUID chamaId, ContributionRules rules) {
        Map<UUID, Long> asks = new LinkedHashMap<>();
        for (SavingsProductSummary product : products.products(chamaId)) {
            if (product.defaultProduct()) {
                asks.put(product.id(), rules.amountMinor());
            } else if (product.active() && product.contributionMinor() > 0) {
                asks.put(product.id(), product.contributionMinor());
            }
        }
        return asks;
    }

    private void syncDues(UUID chamaId) {
        List<ContributionPeriod> periods = repository.periods(chamaId);
        if (periods.isEmpty()) {
            return;
        }
        Set<UUID> seated = members.seatedMembers(chamaId).stream().map(MemberSummary::userId).collect(Collectors.toSet());
        if (seated.isEmpty()) {
            return;
        }
        Map<UUID, Map<UUID, Long>> asked = repository.periodProducts(chamaId);
        // A period opened without a breakdown per product asks its whole amount for the default product.
        UUID defaultProduct = products.defaultProduct(chamaId).id();
        for (ContributionPeriod period : periods) {
            Map<UUID, Long> asks = asked.get(period.id());
            if (asks == null || asks.isEmpty()) {
                asked.put(period.id(), Map.of(defaultProduct, period.amountDueMinor()));
            }
        }
        Set<String> existing = new HashSet<>();
        repository.dues(chamaId).forEach(d -> existing.add(d.periodId() + "/" + d.memberUserId() + "/" + d.productId()));

        List<DueRow> missing = new ArrayList<>();
        for (MemberAccountSummary account : accounts.accounts(chamaId)) {
            if (!seated.contains(account.memberUserId())) {
                continue;
            }
            for (ContributionPeriod period : periods) {
                Long amount = asked.getOrDefault(period.id(), Map.of()).get(account.productId());
                if (amount == null || !account.owesFor(period.dueDate())) {
                    continue;
                }
                if (!existing.contains(period.id() + "/" + account.memberUserId() + "/" + account.productId())) {
                    missing.add(new DueRow(period.id(), account.memberUserId(), account.productId(), amount));
                }
            }
        }
        if (!missing.isEmpty()) {
            repository.insertDues(chamaId, missing);
        }
    }
}
