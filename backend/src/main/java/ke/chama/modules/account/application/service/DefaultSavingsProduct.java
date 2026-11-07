package ke.chama.modules.account.application.service;

import ke.chama.modules.account.application.port.out.AccountRepository;
import ke.chama.modules.account.domain.model.SavingsProduct;
import ke.chama.modules.chama.api.ChamaDirectory;
import ke.chama.modules.chama.api.ContributionRules;
import ke.chama.modules.chama.domain.event.ChamaCreated;
import ke.chama.modules.chama.domain.event.ChamaSettingsChanged;
import ke.chama.modules.transaction.api.Ledger;
import ke.chama.modules.transaction.api.LedgerAccountKey;
import ke.chama.modules.transaction.api.LedgerAccountSummary;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * The default savings product, Deposits, on the ledger's "Member savings" account. Created with the
 * chama, in the same transaction, after the chart of accounts; its contribution is the chama's
 * contribution amount and follows every change to the settings. (Chamas created before products
 * existed got theirs from migration V20.)
 */
@Component
class DefaultSavingsProduct {

    private final AccountRepository repository;
    private final ChamaDirectory chamas;
    private final Ledger ledger;
    private final Clock clock;

    DefaultSavingsProduct(AccountRepository repository, ChamaDirectory chamas, Ledger ledger, Clock clock) {
        this.repository = repository;
        this.chamas = chamas;
        this.ledger = ledger;
        this.clock = clock;
    }

    @EventListener
    @Order(Ordered.LOWEST_PRECEDENCE)
    @Transactional(propagation = Propagation.MANDATORY)
    void on(ChamaCreated event) {
        if (repository.defaultProduct(event.chamaId()).isPresent()) {
            return;
        }
        String ledgerCode = ledger.account(event.chamaId(), LedgerAccountKey.MEMBER_SAVINGS)
                .map(LedgerAccountSummary::code)
                .orElseThrow(() -> new IllegalStateException("The chart of accounts must exist before savings products"));
        long amount = chamas.contributionRules(event.chamaId()).map(ContributionRules::amountMinor).orElseThrow();
        repository.insertProduct(SavingsProduct.defaultDeposits(event.chamaId(), ledgerCode, amount, clock.instant()));
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    void on(ChamaSettingsChanged event) {
        long amount = chamas.contributionRules(event.chamaId()).map(ContributionRules::amountMinor).orElseThrow();
        repository.defaultProduct(event.chamaId())
                .filter(product -> product.contributionMinor() != amount)
                .ifPresent(product -> {
                    if (!repository.updateProduct(product.followContributionAmount(amount, clock.instant()))) {
                        throw new OptimisticLockingFailureException("Savings product " + product.id() + " changed concurrently");
                    }
                });
    }
}
