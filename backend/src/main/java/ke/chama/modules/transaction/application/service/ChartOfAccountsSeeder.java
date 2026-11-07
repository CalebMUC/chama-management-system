package ke.chama.modules.transaction.application.service;

import ke.chama.modules.chama.domain.event.ChamaCreated;
import ke.chama.modules.transaction.application.port.out.LedgerRepository;
import ke.chama.modules.transaction.domain.model.DefaultChartOfAccounts;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Currency;

/**
 * Opens a new chama's books in the SAME transaction that creates the chama, so no chama exists
 * without a chart of accounts.
 */
@Component
class ChartOfAccountsSeeder {

    private final LedgerRepository repository;

    ChartOfAccountsSeeder(LedgerRepository repository) {
        this.repository = repository;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    void on(ChamaCreated event) {
        repository.insertAccounts(DefaultChartOfAccounts.forChama(event.chamaId(),
                Currency.getInstance(event.currency()), event.occurredAt()));
        repository.initSequence(event.chamaId());
    }
}
