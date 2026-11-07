package ke.chama.modules.investment.application.service;

import ke.chama.modules.expense.api.InvestmentLookup;
import ke.chama.modules.investment.application.port.out.InvestmentRepository;
import ke.chama.modules.investment.domain.model.Investment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/** Lets the expense module charge an expense to one of the chama's active investments. */
@Component
class InvestmentLookupForExpenses implements InvestmentLookup {

    private final InvestmentRepository repository;

    InvestmentLookupForExpenses(InvestmentRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LinkedInvestment> find(UUID chamaId, UUID investmentId) {
        return repository.investment(chamaId, investmentId)
                .map(i -> new LinkedInvestment(i.id(), i.name(), i.status() == Investment.Status.ACTIVE));
    }
}
