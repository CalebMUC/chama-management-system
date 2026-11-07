package ke.chama.modules.penalty.infrastructure.scheduling;

import ke.chama.modules.chama.api.ChamaDirectory;
import ke.chama.modules.chama.api.ChamaRegistry;
import ke.chama.modules.chama.api.LicenseSummary;
import ke.chama.modules.penalty.application.port.in.PenaltyUseCases;
import ke.chama.shared.tenancy.LicenseFeature;
import ke.chama.shared.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * Every night, penalises late contributions in every chama whose licence includes contributions
 * and penalties, so members are penalised (and emailed) even if nobody opens the app. Each chama
 * runs as itself, in its own transaction: one failing chama does not stop the others.
 */
@Component
class LatePenaltyJob {

    private static final Logger log = LoggerFactory.getLogger(LatePenaltyJob.class);

    private final ChamaRegistry registry;
    private final ChamaDirectory chamas;
    private final PenaltyUseCases penalties;
    private final TransactionTemplate transaction;

    LatePenaltyJob(ChamaRegistry registry, ChamaDirectory chamas, PenaltyUseCases penalties,
                   PlatformTransactionManager transactionManager) {
        this.registry = registry;
        this.chamas = chamas;
        this.penalties = penalties;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Scheduled(cron = "${chama.penalties.assessment-cron:0 0 1 * * *}", zone = "${chama.time-zone:Africa/Nairobi}")
    void assessAllChamas() {
        int total = 0;
        for (UUID chamaId : registry.activeChamaIds()) {
            try {
                total += TenantContext.callAs(chamaId, null, () -> transaction.execute(status -> assess(chamaId)));
            } catch (RuntimeException e) {
                log.error("Late penalty assessment failed for chama {}", chamaId, e);
            }
        }
        if (total > 0) {
            log.info("Late penalty assessment: {} new penalt{}", total, total == 1 ? "y" : "ies");
        }
    }

    private int assess(UUID chamaId) {
        boolean licensed = chamas.license(chamaId)
                .filter(LicenseSummary::usable)
                .filter(l -> l.includes(LicenseFeature.CONTRIBUTIONS) && l.includes(LicenseFeature.PENALTIES))
                .isPresent();
        return licensed ? penalties.assessLateContributions(chamaId).size() : 0;
    }
}
