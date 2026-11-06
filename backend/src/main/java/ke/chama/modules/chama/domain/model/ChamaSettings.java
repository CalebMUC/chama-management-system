package ke.chama.modules.chama.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import ke.chama.shared.domain.exception.DomainValidationException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The chama's own rules, read by the contribution, penalty and loan modules. Rates are basis
 * points (100 bp = 1%) and amounts are minor units, so nothing involves floating point.
 *
 * @param contributionAmountMinor     expected contribution per cycle, e.g. 500000 = KES 5,000
 * @param contributionDueDay          day of month (MONTHLY) or day of week (WEEKLY)
 * @param lateGraceDays               days after the due day before a contribution counts as late
 * @param latePenaltyBasisPoints      penalty per late cycle, as a share of the contribution
 * @param loanMaxMultiplier           a loan may be at most this many times the member's savings
 * @param loanInterestBasisPoints     flat interest per agreed term
 * @param loanMinMembershipMonths     months of membership before a member may borrow
 * @param loanGuarantorThresholdMinor loans above this need guarantors
 * @param loanGuarantorsRequired      how many guarantors such loans need
 * @param approvalQuorum              distinct officials who must approve a loan or expense
 */
@Embeddable
public record ChamaSettings(
        @Column(name = "contribution_amount_minor", nullable = false) long contributionAmountMinor,
        @Enumerated(EnumType.STRING)
        @Column(name = "contribution_frequency", nullable = false, length = 20) ContributionFrequency contributionFrequency,
        @Column(name = "contribution_due_day", nullable = false) int contributionDueDay,
        @Column(name = "late_grace_days", nullable = false) int lateGraceDays,
        @Column(name = "late_penalty_bp", nullable = false) int latePenaltyBasisPoints,
        @Column(name = "loan_max_multiplier", nullable = false) int loanMaxMultiplier,
        @Column(name = "loan_interest_bp", nullable = false) int loanInterestBasisPoints,
        @Column(name = "loan_min_membership_months", nullable = false) int loanMinMembershipMonths,
        @Column(name = "loan_guarantor_threshold_minor", nullable = false) long loanGuarantorThresholdMinor,
        @Column(name = "loan_guarantors_required", nullable = false) int loanGuarantorsRequired,
        @Column(name = "approval_quorum", nullable = false) int approvalQuorum) {

    public ChamaSettings {
        require(contributionAmountMinor > 0, "contributionAmount must be positive");
        require(contributionFrequency != null, "contributionFrequency is required");
        int maxDueDay = contributionFrequency == ContributionFrequency.WEEKLY ? 7 : 28;
        require(contributionDueDay >= 1 && contributionDueDay <= maxDueDay,
                "contributionDueDay must be between 1 and " + maxDueDay + " for " + contributionFrequency);
        require(lateGraceDays >= 0 && lateGraceDays <= 31, "lateGraceDays must be between 0 and 31");
        require(latePenaltyBasisPoints >= 0 && latePenaltyBasisPoints <= 10_000, "latePenalty must be between 0% and 100%");
        require(loanMaxMultiplier >= 1 && loanMaxMultiplier <= 10, "loanMaxMultiplier must be between 1 and 10");
        require(loanInterestBasisPoints >= 0 && loanInterestBasisPoints <= 10_000, "loanInterest must be between 0% and 100%");
        require(loanMinMembershipMonths >= 0 && loanMinMembershipMonths <= 60, "loanMinMembershipMonths must be between 0 and 60");
        require(loanGuarantorThresholdMinor >= 0, "loanGuarantorThreshold must not be negative");
        require(loanGuarantorsRequired >= 0 && loanGuarantorsRequired <= 5, "loanGuarantorsRequired must be between 0 and 5");
        require(approvalQuorum >= 1 && approvalQuorum <= 10, "approvalQuorum must be between 1 and 10");
    }

    /** Common Kenyan chama defaults (see README "Core Business Rules"), in KES. */
    public static ChamaSettings standard() {
        return new ChamaSettings(500_000, ContributionFrequency.MONTHLY, 5, 5, 200,
                3, 1_000, 3, 5_000_000, 2, 2);
    }

    /** For audit details: field name to value. */
    public Map<String, Object> asMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("contributionAmountMinor", contributionAmountMinor);
        map.put("contributionFrequency", contributionFrequency.name());
        map.put("contributionDueDay", contributionDueDay);
        map.put("lateGraceDays", lateGraceDays);
        map.put("latePenaltyBasisPoints", latePenaltyBasisPoints);
        map.put("loanMaxMultiplier", loanMaxMultiplier);
        map.put("loanInterestBasisPoints", loanInterestBasisPoints);
        map.put("loanMinMembershipMonths", loanMinMembershipMonths);
        map.put("loanGuarantorThresholdMinor", loanGuarantorThresholdMinor);
        map.put("loanGuarantorsRequired", loanGuarantorsRequired);
        map.put("approvalQuorum", approvalQuorum);
        return map;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new DomainValidationException("CHAMA_INVALID_SETTINGS", message);
        }
    }
}
