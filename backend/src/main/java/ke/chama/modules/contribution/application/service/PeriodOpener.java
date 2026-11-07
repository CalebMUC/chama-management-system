package ke.chama.modules.contribution.application.service;

import ke.chama.modules.chama.api.ChamaDirectory;
import ke.chama.modules.chama.api.ContributionRules;
import ke.chama.modules.contribution.application.port.out.ContributionRepository;
import ke.chama.modules.contribution.application.port.out.ContributionRepository.DueRow;
import ke.chama.modules.contribution.domain.model.ContributionPeriod;
import ke.chama.modules.contribution.domain.model.PeriodSchedule;
import ke.chama.modules.contribution.domain.model.PeriodSchedule.Frequency;
import ke.chama.modules.contribution.domain.model.PeriodSchedule.PeriodSpan;
import ke.chama.modules.member.api.MemberDirectory;
import ke.chama.modules.member.api.MemberSummary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps a chama's periods and dues up to date. Idempotent and cheap, so it runs at the start of
 * every contribution operation instead of relying on a scheduled job that could miss a cycle:
 * <ol>
 *     <li>opens every period from the last one up to the one containing today (a chama's first
 *     period is the current one), each with the amount in force when it opens;</li>
 *     <li>gives every seated member a due for each period whose due date is on or after the day
 *     they joined. Existing dues are never changed.</li>
 * </ol>
 */
@Component
class PeriodOpener {

    /** Guards against runaway catch-up, e.g. a clock far in the future: about 20 years of months. */
    static final int MAX_PERIODS_PER_CATCH_UP = 260;

    private final ContributionRepository repository;
    private final ChamaDirectory chamas;
    private final MemberDirectory members;
    private final Clock clock;

    PeriodOpener(ContributionRepository repository, ChamaDirectory chamas, MemberDirectory members, Clock clock) {
        this.repository = repository;
        this.chamas = chamas;
        this.members = members;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void bringUpToDate(UUID chamaId, ZoneId zone) {
        ContributionRules rules = chamas.contributionRules(chamaId).orElseThrow();
        LocalDate today = LocalDate.now(clock.withZone(zone));
        Frequency frequency = Frequency.valueOf(rules.frequency());

        Optional<ContributionPeriod> latest = repository.latestPeriod(chamaId);
        PeriodSpan next = latest.isEmpty()
                ? PeriodSchedule.containing(today, frequency, rules.dueDay())
                : PeriodSchedule.after(latest.get().endDate(), frequency, rules.dueDay());
        for (int i = 0; i < MAX_PERIODS_PER_CATCH_UP && !next.start().isAfter(today); i++) {
            repository.insertPeriod(ContributionPeriod.open(chamaId, next, rules.amountMinor(), rules.currency(),
                    clock.instant()));
            next = PeriodSchedule.after(next.end(), frequency, rules.dueDay());
        }

        syncDues(chamaId, zone);
    }

    private void syncDues(UUID chamaId, ZoneId zone) {
        List<ContributionPeriod> periods = repository.periods(chamaId);
        List<MemberSummary> seated = members.seatedMembers(chamaId);
        if (periods.isEmpty() || seated.isEmpty()) {
            return;
        }
        Set<String> existing = new HashSet<>();
        repository.dues(chamaId).forEach(d -> existing.add(d.periodId() + "/" + d.memberUserId()));

        List<DueRow> missing = new ArrayList<>();
        for (MemberSummary member : seated) {
            if (member.joinedAt() == null) {
                continue;
            }
            LocalDate joined = member.joinedAt().atZone(zone).toLocalDate();
            for (ContributionPeriod period : periods) {
                if (!period.dueDate().isBefore(joined) && !existing.contains(period.id() + "/" + member.userId())) {
                    missing.add(new DueRow(period.id(), member.userId(), period.amountDueMinor()));
                }
            }
        }
        if (!missing.isEmpty()) {
            repository.insertDues(chamaId, missing);
        }
    }
}
