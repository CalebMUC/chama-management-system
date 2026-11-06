package ke.chama.modules.chama.presentation.dto.request;

import jakarta.validation.constraints.NotNull;
import ke.chama.modules.chama.domain.model.ChamaSettings;
import ke.chama.modules.chama.domain.model.ContributionFrequency;

/**
 * Amounts in minor units (KES cents: 500000 = KES 5,000); rates in basis points (200 = 2%).
 * Range rules live in {@link ChamaSettings} and return CHAMA_INVALID_SETTINGS.
 */
public record ChamaSettingsRequest(
        @NotNull Long contributionAmountMinor,
        @NotNull ContributionFrequency contributionFrequency,
        @NotNull Integer contributionDueDay,
        @NotNull Integer lateGraceDays,
        @NotNull Integer latePenaltyBasisPoints,
        @NotNull Integer loanMaxMultiplier,
        @NotNull Integer loanInterestBasisPoints,
        @NotNull Integer loanMinMembershipMonths,
        @NotNull Long loanGuarantorThresholdMinor,
        @NotNull Integer loanGuarantorsRequired,
        @NotNull Integer approvalQuorum) {

    public ChamaSettings toSettings() {
        return new ChamaSettings(contributionAmountMinor, contributionFrequency, contributionDueDay, lateGraceDays,
                latePenaltyBasisPoints, loanMaxMultiplier, loanInterestBasisPoints, loanMinMembershipMonths,
                loanGuarantorThresholdMinor, loanGuarantorsRequired, approvalQuorum);
    }
}
