package ke.chama.modules.penalty.application.service;

import ke.chama.modules.penalty.api.PenaltyReports;
import ke.chama.modules.penalty.application.port.out.PenaltyRepository;
import ke.chama.modules.penalty.domain.model.Penalty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Read-only penalty figures for dashboards. */
@Service
@Transactional(readOnly = true)
class PenaltyReportsService implements PenaltyReports {

    private final PenaltyRepository repository;
    private final String zone;

    PenaltyReportsService(PenaltyRepository repository, @Value("${chama.time-zone:Africa/Nairobi}") String timeZone) {
        this.repository = repository;
        this.zone = ZoneId.of(timeZone).getId();
    }

    @Override
    public PenaltySummary summary(UUID chamaId, LocalDate from, LocalDate to, int topOwing) {
        return repository.summary(chamaId, from, to, zone, Math.max(topOwing, 0));
    }

    @Override
    public MemberPenalties ofMember(UUID chamaId, UUID memberUserId) {
        List<Penalty> all = repository.penaltiesOf(chamaId, memberUserId);
        List<OwedItem> owed = all.stream().filter(p -> p.status() == Penalty.Status.OUTSTANDING)
                .sorted(Comparator.comparing(Penalty::assessedOn))
                .map(p -> new OwedItem(p.id(), p.kind().name(), p.description(), p.assessedOn(), p.amountMinor(),
                        p.outstandingMinor()))
                .toList();
        return new MemberPenalties(owed.stream().mapToLong(OwedItem::outstandingMinor).sum(),
                all.stream().mapToLong(Penalty::paidMinor).sum(), all.stream().mapToLong(Penalty::waivedMinor).sum(),
                all.size(), owed);
    }
}
