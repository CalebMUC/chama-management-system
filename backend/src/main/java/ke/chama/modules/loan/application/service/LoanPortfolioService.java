package ke.chama.modules.loan.application.service;

import ke.chama.modules.loan.api.LoanPortfolio;
import ke.chama.modules.loan.application.port.out.LoanRepository;
import ke.chama.modules.loan.domain.model.Loan;
import ke.chama.modules.loan.domain.model.LoanProduct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read-only snapshots of the chama's loans for reports and dashboards. */
@Service
@Transactional(readOnly = true)
class LoanPortfolioService implements LoanPortfolio {

    private final LoanRepository repository;
    private final LoanProductService products;
    private final Clock clock;
    private final ZoneId zone;

    LoanPortfolioService(LoanRepository repository, LoanProductService products, Clock clock,
                         @Value("${chama.time-zone:Africa/Nairobi}") String timeZone) {
        this.repository = repository;
        this.products = products;
        this.clock = clock;
        this.zone = ZoneId.of(timeZone);
    }

    @Override
    public List<LoanSnapshot> loans(UUID chamaId) {
        return snapshots(chamaId, repository.allLoans(chamaId));
    }

    @Override
    public List<LoanSnapshot> loansOf(UUID chamaId, UUID memberUserId) {
        return snapshots(chamaId, repository.loansOf(chamaId, memberUserId));
    }

    @Override
    public int pendingGuaranteeRequests(UUID chamaId, UUID memberUserId) {
        return (int) repository.guaranteedBy(chamaId, memberUserId).stream()
                .filter(l -> l.status() == Loan.Status.PENDING_GUARANTORS)
                .filter(l -> l.guarantor(memberUserId).map(g -> g.status() == Loan.Guarantor.Status.REQUESTED).orElse(false))
                .count();
    }

    private List<LoanSnapshot> snapshots(UUID chamaId, List<Loan> loans) {
        Map<UUID, LoanProduct> catalog = products.byId(chamaId);
        LocalDate today = LocalDate.now(clock.withZone(zone));
        return loans.stream().map(l -> {
            LoanProduct product = catalog.get(l.productId());
            Loan.Instalment next = l.status() == Loan.Status.ACTIVE ? l.nextInstalment(today).orElse(null) : null;
            return new LoanSnapshot(l.id(), l.borrowerUserId(), l.productId(), product == null ? null : product.code(),
                    product == null ? null : product.name(), l.status().name(), l.currency(), l.principalMinor(),
                    l.interestMinor(), l.totalDueMinor(), l.repaidMinor(), l.outstandingMinor(), l.arrearsMinor(today),
                    l.daysInArrears(today), l.termMonths(), l.purpose(), l.appliedOn(), l.disbursedOn(),
                    l.maturityDate(), next == null ? null : next.dueDate(), next == null ? 0 : next.unpaidMinor());
        }).toList();
    }
}
