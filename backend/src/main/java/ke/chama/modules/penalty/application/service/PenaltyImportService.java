package ke.chama.modules.penalty.application.service;

import ke.chama.modules.audit.api.AuditEntry;
import ke.chama.modules.audit.api.AuditTrail;
import ke.chama.modules.penalty.api.PenaltyImport;
import ke.chama.modules.penalty.application.port.out.PenaltyRepository;
import ke.chama.modules.penalty.domain.model.Penalty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/** Brings over penalties still owed from a chama's spreadsheets, in the importer's transaction. */
@Service
class PenaltyImportService implements PenaltyImport {

    private final PenaltyRepository repository;
    private final AuditTrail auditTrail;
    private final Clock clock;

    PenaltyImportService(PenaltyRepository repository, AuditTrail auditTrail, Clock clock) {
        this.repository = repository;
        this.auditTrail = auditTrail;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID importOutstanding(UUID chamaId, UUID memberUserId, long amountMinor, String currency, LocalDate asOf,
                                  UUID importedBy, UUID openingEntryId) {
        Penalty penalty = Penalty.broughtForward(chamaId, memberUserId, amountMinor, currency, asOf, importedBy,
                openingEntryId, clock.instant());
        repository.insert(penalty);
        auditTrail.record(AuditEntry.of(chamaId, importedBy, "PENALTY_IMPORTED", PenaltyService.AUDIT_TARGET, penalty.id())
                .with(Map.of("memberUserId", memberUserId.toString(), "amountMinor", amountMinor)));
        return penalty.id();
    }
}
