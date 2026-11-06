package ke.chama.modules.chama.presentation.dto.response;

import ke.chama.modules.chama.application.port.in.ChamaView;
import ke.chama.modules.chama.domain.model.ChamaSettings;

import java.time.Instant;
import java.util.UUID;

public record ChamaResponse(UUID id,
                            String name,
                            String description,
                            String currency,
                            String status,
                            UUID createdBy,
                            Instant createdAt,
                            Settings settings) {

    public record Settings(long contributionAmountMinor,
                           String contributionFrequency,
                           int contributionDueDay,
                           int lateGraceDays,
                           int latePenaltyBasisPoints,
                           int loanMaxMultiplier,
                           int loanInterestBasisPoints,
                           int loanMinMembershipMonths,
                           long loanGuarantorThresholdMinor,
                           int loanGuarantorsRequired,
                           int approvalQuorum) {

        static Settings from(ChamaSettings s) {
            return new Settings(s.contributionAmountMinor(), s.contributionFrequency().name(), s.contributionDueDay(),
                    s.lateGraceDays(), s.latePenaltyBasisPoints(), s.loanMaxMultiplier(), s.loanInterestBasisPoints(),
                    s.loanMinMembershipMonths(), s.loanGuarantorThresholdMinor(), s.loanGuarantorsRequired(),
                    s.approvalQuorum());
        }
    }

    public static ChamaResponse from(ChamaView view) {
        return new ChamaResponse(view.id(), view.name(), view.description(), view.currency(), view.status(),
                view.createdBy(), view.createdAt(), Settings.from(view.settings()));
    }
}
