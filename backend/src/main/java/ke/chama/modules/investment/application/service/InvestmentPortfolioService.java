package ke.chama.modules.investment.application.service;

import ke.chama.modules.investment.api.InvestmentPortfolio;
import ke.chama.modules.investment.application.port.out.InvestmentRepository;
import ke.chama.modules.investment.domain.model.Investment;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Read-only view of the chama's investments for reports and dashboards. */
@Service
@Transactional(readOnly = true)
class InvestmentPortfolioService implements InvestmentPortfolio {

    private final InvestmentRepository repository;

    InvestmentPortfolioService(InvestmentRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<InvestmentSnapshot> fundedInvestments(UUID chamaId) {
        return repository.fundedInvestments(chamaId).stream()
                .sorted(Comparator.comparing(Investment::fundedOn, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(i -> new InvestmentSnapshot(i.id(), i.name(), i.type().name(), i.status().name(), i.investedMinor(),
                        i.bookValueMinor(), i.marketValueMinor(), i.incomeMinor(), i.fundedOn(), i.closedOn()))
                .toList();
    }

    @Override
    public long awaitingApproval(UUID chamaId) {
        return repository.investments(chamaId, Investment.Status.PROPOSED, null, PageRequest.of(0, 1)).getTotalElements();
    }
}
