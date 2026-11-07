package ke.chama.modules.loan.application.service;

import ke.chama.modules.audit.api.AuditEntry;
import ke.chama.modules.audit.api.AuditTrail;
import ke.chama.modules.loan.api.LoanImport;
import ke.chama.modules.loan.application.port.out.LoanRepository;
import ke.chama.modules.loan.domain.model.Loan;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Brings over loans from a chama's spreadsheets, in the importer's transaction. */
@Service
class LoanImportService implements LoanImport {

    private final LoanRepository repository;
    private final AuditTrail auditTrail;
    private final Clock clock;

    LoanImportService(LoanRepository repository, AuditTrail auditTrail, Clock clock) {
        this.repository = repository;
        this.auditTrail = auditTrail;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> borrowersWithOpenLoans(UUID chamaId) {
        return repository.borrowersWithOpenLoans(chamaId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID importLoan(UUID chamaId, ImportedLoan l, UUID importedBy, UUID openingEntryId) {
        Loan loan = Loan.imported(chamaId, l.borrowerUserId(), l.principalMinor(), l.interestMinor(), l.termMonths(),
                l.disbursedOn(), l.repaidMinor(), l.purpose(), l.currency(), importedBy, openingEntryId, clock.instant());
        repository.insert(loan);
        auditTrail.record(AuditEntry.of(chamaId, importedBy, "LOAN_IMPORTED", LoanService.AUDIT_TARGET, loan.id())
                .with(Map.of("borrowerUserId", l.borrowerUserId().toString(), "principalMinor", l.principalMinor(),
                        "interestMinor", l.interestMinor(), "repaidMinor", l.repaidMinor(),
                        "outstandingMinor", loan.outstandingMinor(), "disbursedOn", l.disbursedOn().toString())));
        return loan.id();
    }
}
