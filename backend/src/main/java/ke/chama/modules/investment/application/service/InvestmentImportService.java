package ke.chama.modules.investment.application.service;

import ke.chama.modules.audit.api.AuditEntry;
import ke.chama.modules.audit.api.AuditTrail;
import ke.chama.modules.investment.api.InvestmentImport;
import ke.chama.modules.investment.application.port.out.InvestmentRepository;
import ke.chama.modules.investment.domain.model.Investment;
import ke.chama.modules.investment.domain.model.InvestmentTransaction;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Brings over investments the chama already holds, in the importer's transaction. */
@Service
class InvestmentImportService implements InvestmentImport {

    /** The method on the funding transaction of an imported investment. */
    static final String OPENING_BALANCE = "OPENING_BALANCE";

    private final InvestmentRepository repository;
    private final AuditTrail auditTrail;
    private final Clock clock;

    InvestmentImportService(InvestmentRepository repository, AuditTrail auditTrail, Clock clock) {
        this.repository = repository;
        this.auditTrail = auditTrail;
        this.clock = clock;
    }

    @Override
    public Set<String> typeNames() {
        return Arrays.stream(Investment.Type.values()).map(Enum::name).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID importInvestment(UUID chamaId, ImportedInvestment i, UUID importedBy, UUID openingEntryId) {
        Instant now = clock.instant();
        Investment investment = Investment.imported(chamaId, importedBy, i.name(),
                Investment.Type.valueOf(i.type().trim().toUpperCase(Locale.ROOT)), i.institution(), i.amountMinor(),
                i.currency(), i.expectedReturnBasisPoints(), i.investedOn(), i.currentValueMinor(), i.asOf(), now);
        repository.insert(investment);
        // The insert records the proposal columns; this writes the funded state.
        if (!repository.update(investment)) {
            throw new OptimisticLockingFailureException("Investment " + investment.id() + " changed concurrently");
        }
        repository.insertTransaction(new InvestmentTransaction(UUID.randomUUID(), chamaId, investment.id(),
                InvestmentTransaction.Kind.FUNDING, i.amountMinor(), i.currency(), OPENING_BALANCE, null, i.investedOn(),
                "Brought forward from the chama's records", openingEntryId, importedBy, now));
        if (i.currentValueMinor() != null) {
            repository.insertValuation(new Investment.Valuation(UUID.randomUUID(), chamaId, investment.id(),
                    i.currentValueMinor(), i.asOf(), "Value brought forward", importedBy, now));
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("name", investment.name());
        details.put("type", investment.type().name());
        details.put("amountMinor", i.amountMinor());
        if (i.currentValueMinor() != null) {
            details.put("currentValueMinor", i.currentValueMinor());
        }
        auditTrail.record(AuditEntry.of(chamaId, importedBy, "INVESTMENT_IMPORTED", InvestmentService.AUDIT_TARGET,
                investment.id()).with(details));
        return investment.id();
    }
}
