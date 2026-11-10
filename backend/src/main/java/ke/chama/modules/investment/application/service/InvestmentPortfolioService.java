package ke.chama.modules.investment.application.service;

import ke.chama.modules.expense.api.ExpenseTotals;
import ke.chama.modules.investment.api.InvestmentPortfolio;
import ke.chama.modules.investment.application.port.out.InvestmentRepository;
import ke.chama.modules.investment.domain.model.Investment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read-only view of the chama's investments for reports and dashboards, with each one's returns. */
@Service
@Transactional(readOnly = true)
class InvestmentPortfolioService implements InvestmentPortfolio {

    private final InvestmentRepository repository;
    private final ExpenseTotals expenses;
    private final Clock clock;
    private final ZoneId zone;

    InvestmentPortfolioService(InvestmentRepository repository, ExpenseTotals expenses, Clock clock,
                               @Value("${chama.time-zone:Africa/Nairobi}") String timeZone) {
        this.repository = repository;
        this.expenses = expenses;
        this.clock = clock;
        this.zone = ZoneId.of(timeZone);
    }

    @Override
    public List<InvestmentSnapshot> fundedInvestments(UUID chamaId) {
        Map<UUID, Long> spent = expenses.paidByInvestment(chamaId);
        LocalDate today = LocalDate.now(clock.withZone(zone));
        return repository.fundedInvestments(chamaId).stream()
                .sorted(Comparator.comparing(Investment::fundedOn, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(i -> snapshot(i, spent.getOrDefault(i.id(), 0L), today))
                .toList();
    }

    private static InvestmentSnapshot snapshot(Investment i, long expensesMinor, LocalDate today) {
        Investment.Performance p = i.performance(expensesMinor, today);
        return new InvestmentSnapshot(i.id(), i.name(), i.type().name(), i.status().name(), i.investedMinor(),
                i.bookValueMinor(), i.marketValueMinor(), i.incomeMinor(), i.fundedOn(), i.closedOn(), i.institution(),
                i.maturityDate(), expensesMinor, p == null ? 0 : p.totalReturnMinor(), p == null ? 0 : p.returnBasisPoints(),
                p == null ? null : p.annualisedReturnBasisPoints(), p == null ? 0 : p.daysHeld());
    }

    @Override
    public long awaitingApproval(UUID chamaId) {
        return repository.investments(chamaId, Investment.Status.PROPOSED, null, PageRequest.of(0, 1)).getTotalElements();
    }
}
