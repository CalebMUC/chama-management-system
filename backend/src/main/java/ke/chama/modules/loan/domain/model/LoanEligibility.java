package ke.chama.modules.loan.domain.model;

import ke.chama.modules.chama.api.LoanRules;
import ke.chama.modules.loan.domain.exception.LoanExceptions.NotEligibleException;

import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;

/**
 * Whether a member may borrow, and how much: at most {@code maxMultiplier} times their savings,
 * after {@code minMembershipMonths} of membership, while active, and with no other loan open
 * (a member clears one loan before taking the next).
 *
 * @param reasons why the member cannot borrow; empty when they can
 */
public record LoanEligibility(LoanRules rules,
                              long savingsMinor,
                              long maxLoanMinor,
                              int membershipMonths,
                              List<String> reasons) {

    public static LoanEligibility assess(LoanRules rules, boolean activeMember, long savingsMinor, LocalDate joinedOn,
                                         LocalDate today, boolean hasOpenLoan) {
        int months = joinedOn == null ? 0 : (int) Math.max(Period.between(joinedOn, today).toTotalMonths(), 0);
        long max = Math.max(savingsMinor, 0) * rules.maxMultiplier();
        List<String> reasons = new ArrayList<>();
        if (!activeMember) {
            reasons.add("Only active members can borrow.");
        }
        if (months < rules.minMembershipMonths()) {
            reasons.add("Members can borrow after " + rules.minMembershipMonths() + " months of membership; you have "
                    + months + ".");
        }
        if (savingsMinor <= 0) {
            reasons.add("You need savings with the chama before you can borrow.");
        }
        if (hasOpenLoan) {
            reasons.add("You already have a loan in progress. Repay or cancel it before applying again.");
        }
        return new LoanEligibility(rules, savingsMinor, max, months, List.copyOf(reasons));
    }

    public boolean eligible() {
        return reasons.isEmpty();
    }

    /** Throws unless the member may borrow {@code principalMinor}. */
    public void requireAllows(long principalMinor) {
        List<String> all = new ArrayList<>(reasons);
        if (savingsMinor > 0 && principalMinor > maxLoanMinor) {
            all.add("The most you can borrow is " + Loan.formatted(maxLoanMinor, rules.currency()) + " ("
                    + rules.maxMultiplier() + "× your savings of " + Loan.formatted(savingsMinor, rules.currency()) + ").");
        }
        if (!all.isEmpty()) {
            throw new NotEligibleException(all);
        }
    }
}
