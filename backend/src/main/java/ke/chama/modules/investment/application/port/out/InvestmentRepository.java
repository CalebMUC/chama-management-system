package ke.chama.modules.investment.application.port.out;

import ke.chama.modules.investment.domain.model.Investment;
import ke.chama.modules.investment.domain.model.InvestmentTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Row-level security limits every query to the current chama. Investments load with their approvals. */
public interface InvestmentRepository {

    void insert(Investment investment);

    /** Saves the investment and its approvals; false if it changed since it was read. */
    boolean update(Investment investment);

    Optional<Investment> investment(UUID chamaId, UUID investmentId);

    Page<Investment> investments(UUID chamaId, Investment.Status status, Investment.Type type, Pageable pageable);

    /** Active and closed investments, for the portfolio. */
    List<Investment> fundedInvestments(UUID chamaId);

    void insertTransaction(InvestmentTransaction transaction);

    List<InvestmentTransaction> transactions(UUID chamaId, UUID investmentId);

    void insertValuation(Investment.Valuation valuation);

    List<Investment.Valuation> valuations(UUID chamaId, UUID investmentId);

    boolean referenceExists(UUID chamaId, String method, String reference);
}
